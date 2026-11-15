#!/usr/bin/env bash
# Proves zsign-octo signs every bundle with its own entitlements, with profiles and ad hoc, and
# that a missing, unknown or unreadable entry, a profile that cannot load and a bundle without
# its profile each stop the signing. It uses a throwaway CA and identity and fixture profiles
# made here, never the real signing identity.
#   bash ios/scripts/release/test_zsign.sh /root/octo-tools/bin/zsign-octo /root/octo-testflight/unsigned.ipa
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
zsign=$(readlink -f "${1:?usage: test_zsign.sh ZSIGN UNSIGNED.ipa}")
ipa=$(readlink -f "${2:?usage: test_zsign.sh ZSIGN UNSIGNED.ipa}")
reader=$here/octo_asc.py
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"
team=TESTTEAM01
app=app.winters.octo
ids=("$app" "$app.share" "$app.widgets")
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; pass=$((pass + 1)); }

# A throwaway CA and leaf, packed as a p12 so zsign's CMS step finds the issuer in the chain.
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=Octo Test CA" -keyout ca.key -out ca.pem 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=Apple Distribution: Octo Test ($team)/OU=$team" \
  -keyout leaf.key -out leaf.csr 2>/dev/null
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -out leaf.pem 2>/dev/null
openssl pkcs12 -export -inkey leaf.key -in leaf.pem -certfile ca.pem -passout pass:test -out id.p12 2>/dev/null
cert_b64=$(openssl x509 -in leaf.pem -outform DER | base64 -w0)

profile() { # id -> a CMS-wrapped App Store style profile granting the group (and Siri for the app)
  local id=$1 siri=""
  [ "$id" = "$app" ] && siri="<key>com.apple.developer.siri</key><true/>"
  cat > "$id.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Name</key><string>Octo test $id</string>
<key>TeamIdentifier</key><array><string>$team</string></array>
<key>DeveloperCertificates</key><array><data>$cert_b64</data></array>
<key>Entitlements</key><dict>
<key>application-identifier</key><string>$team.$id</string>
<key>com.apple.developer.team-identifier</key><string>$team</string>
<key>keychain-access-groups</key><array><string>$team.*</string><string>com.apple.token</string></array>
<key>get-task-allow</key><false/>
<key>beta-reports-active</key><true/>
<key>com.apple.security.application-groups</key><array><string>group.$app</string></array>
$siri
</dict></dict></plist>
EOF
  openssl cms -sign -nodetach -binary -outform DER -in "$id.plist" -signer leaf.pem -inkey leaf.key -out "$id.mobileprovision"
}

entitlements() { # id mode -> the entitlements file zsign must seal for that bundle
  local id=$1 mode=$2 extra="" base=""
  [ "$id" = "$app" ] && extra="<key>com.apple.developer.siri</key><true/>"
  [ "$mode" = profile ] && base="<key>application-identifier</key><string>$team.$id</string>
<key>com.apple.developer.team-identifier</key><string>$team</string>
<key>get-task-allow</key><false/>
<key>beta-reports-active</key><true/>"
  # The marker makes every bundle's set its own, so a swap between two extensions shows.
  cat > "$id.$mode.entitlements" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
$base
<key>com.apple.security.application-groups</key><array><string>group.$app</string></array>
<key>octo-test-bundle</key><string>$id</string>
$extra
</dict></plist>
EOF
}

for id in "${ids[@]}"; do profile "$id"; entitlements "$id" profile; entitlements "$id" adhoc; done

expect_same() { # signed.ipa mode
  python3 -I - "$reader" "$1" "$2" "${ids[@]}" <<'EOF'
import json, plistlib, subprocess, sys
reader, ipa, mode, ids = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
sealed = json.loads(subprocess.run([sys.executable, "-I", reader, "entitlements", ipa], check=True,
                                   capture_output=True, text=True).stdout)
for i in ids:
    want = plistlib.load(open(f"{i}.{mode}.entitlements", "rb"))
    if sealed.get(i) != want:
        sys.exit(f"{i}: sealed {sealed.get(i)} != {want}")
print("sealed entitlements match for", ", ".join(ids))
EOF
}

be() { local mode=$1; shift; for id in "$@"; do printf '%s\n' --bundle-entitlements "$id=$id.$mode.entitlements"; done; }
mapfile -t all_profile < <(be profile "${ids[@]}")
mapfile -t all_adhoc < <(be adhoc "${ids[@]}")
profiles=(-m "$app.mobileprovision" -m "$app.share.mobileprovision" -m "$app.widgets.mobileprovision")

"$zsign" -k id.p12 -p test "${profiles[@]}" "${all_profile[@]}" -z 1 -o signed.ipa "$ipa" > z1.log 2>&1 \
  || { cat z1.log; fail "profile signing failed"; }
expect_same signed.ipa profile && ok "profile mode: each bundle sealed with its own entitlements"

"$zsign" -a "${all_adhoc[@]}" -z 1 -o adhoc.ipa "$ipa" > z2.log 2>&1 || { cat z2.log; fail "ad hoc signing failed"; }
expect_same adhoc.ipa adhoc && ok "ad hoc mode: each bundle sealed with its own entitlements"

mapfile -t missing < <(be profile "$app" "$app.share")
if "$zsign" -k id.p12 -p test "${profiles[@]}" "${missing[@]}" -z 1 -o x.ipa "$ipa" > z3.log 2>&1; then
  fail "a bundle without an entry was signed"
fi
grep -q "No --bundle-entitlements for $app.widgets" z3.log && ok "a bundle without an entry stops the signing"

if "$zsign" -k id.p12 -p test "${profiles[@]}" "${all_profile[@]}" \
    --bundle-entitlements "app.winters.other=$app.profile.entitlements" -z 1 -o x.ipa "$ipa" > z4.log 2>&1; then
  fail "an unknown id was accepted"
fi
grep -q "names app.winters.other, which is not a bundle" z4.log && ok "an id that is no bundle stops the signing"

if "$zsign" -k id.p12 -p test "${profiles[@]}" --bundle-entitlements "$app=missing.entitlements" \
    -z 1 -o x.ipa "$ipa" > z5.log 2>&1; then
  fail "an unreadable file was accepted"
fi
grep -q "Can't read the entitlements for $app" z5.log && ok "an unreadable entitlements file stops the signing"

# The bad profile goes first: zsign's own setup reads the last -m, so only the patched loop
# over every -m can catch it.
echo "not a profile" > bad.mobileprovision
if "$zsign" -k id.p12 -p test -m bad.mobileprovision -m "$app.mobileprovision" -m "$app.share.mobileprovision" \
    -m "$app.widgets.mobileprovision" "${all_profile[@]}" -z 1 -o x.ipa "$ipa" > z6.log 2>&1; then
  fail "a bad profile was dropped silently"
fi
grep -q "Failed to init provision" z6.log && ok "a profile that cannot load stops the signing"

if "$zsign" -k id.p12 -p test -m "$app.mobileprovision" -m "$app.share.mobileprovision" "${all_profile[@]}" \
    -z 1 -o x.ipa "$ipa" > z7.log 2>&1; then
  fail "a bundle without a profile was signed"
fi
grep -q "No provisioning profile is for $app.widgets" z7.log && ok "a bundle without its profile stops the signing"

[ "$pass" = 7 ] || fail "only $pass of 7 checks passed"
echo "zsign self-test passed (7 checks)"
