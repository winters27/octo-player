#!/usr/bin/env bash
# Remakes the client certificate files the desktop tests open: a test root,
# an intermediate, client certificates of every key kind Octo checks, a
# server certificate for localhost, and files that must be refused. Every
# certificate is good for 100 years; expiry is tested with a set clock.
# Needs OpenSSL 3 and the JDK's keytool. Run from any folder:
#   bash fixtures/gateway/certs/make-certs.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
days=36500
pass=octo-test

root() {
  openssl req -x509 -newkey rsa:2048 -nodes -keyout ca-key.pem -out ca.pem -days "$days" -subj "/CN=Octo test root" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -keyout mid-key.pem -out mid.csr -subj "/CN=Octo test intermediate" 2>/dev/null
  printf 'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n' > mid.ext
  openssl x509 -req -in mid.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out mid.pem -days "$days" -extfile mid.ext 2>/dev/null
}

# leaf <name> <extended key usage> <signer> <genpkey args...>: a key, then a
# certificate from the signer.
leaf() {
  local name="$1" usage="$2" signer="$3"; shift 3
  openssl genpkey "$@" -out "$name-key.pem" 2>/dev/null
  openssl req -new -key "$name-key.pem" -out "$name.csr" -subj "/CN=$name" 2>/dev/null
  printf 'extendedKeyUsage=%s\n' "$usage" > "$name.ext"
  openssl x509 -req -in "$name.csr" -CA "$signer.pem" -CAkey "$signer-key.pem" -CAcreateserial -out "$name.pem" -days "$days" -extfile "$name.ext" 2>/dev/null
}

p12() {
  local name="$1" out="$2" password="$3"; shift 3
  openssl pkcs12 -export -inkey "$name-key.pem" -in "$name.pem" -name client -passout "pass:$password" -out "$here/$out" "$@"
}

root
cp ca.pem "$here/ca.pem"

leaf rsa2048 clientAuth ca -algorithm RSA -pkeyopt rsa_keygen_bits:2048
leaf second-rsa clientAuth ca -algorithm RSA -pkeyopt rsa_keygen_bits:2048
leaf p256 clientAuth ca -algorithm EC -pkeyopt ec_paramgen_curve:P-256
leaf p384 clientAuth ca -algorithm EC -pkeyopt ec_paramgen_curve:P-384
leaf p521 clientAuth ca -algorithm EC -pkeyopt ec_paramgen_curve:P-521
leaf ed25519 clientAuth ca -algorithm ED25519
leaf rsa1024 clientAuth ca -algorithm RSA -pkeyopt rsa_keygen_bits:1024
leaf rsapss clientAuth ca -algorithm RSA-PSS -pkeyopt rsa_keygen_bits:2048
leaf serveronly serverAuth ca -algorithm RSA -pkeyopt rsa_keygen_bits:2048
leaf chained clientAuth mid -algorithm RSA -pkeyopt rsa_keygen_bits:2048

p12 rsa2048 rsa2048.p12 "$pass"
p12 second-rsa second-rsa.p12 "$pass"
p12 p256 p256.p12 "$pass"
p12 p384 p384.p12 "$pass"
p12 p521 p521.p12 "$pass"
p12 ed25519 ed25519.p12 "$pass"
p12 rsa1024 rsa1024.p12 "$pass"
p12 rsapss rsapss.p12 "$pass"
p12 serveronly server-auth-only.p12 "$pass"
p12 rsa2048 empty-password.p12 ""
p12 rsa2048 legacy.p12 "$pass" -legacy
p12 chained chain-leaf-only.p12 "$pass"
p12 chained chain.p12 "$pass" -certfile mid.pem
openssl pkcs12 -export -nokeys -in rsa2048.pem -passout "pass:$pass" -out "$here/no-key.p12"

# Two key entries, one only for servers, as keytool joins them.
rm -f "$here/two-entries.p12"
"$keytool" -importkeystore -srckeystore "$here/server-auth-only.p12" -srcstoretype PKCS12 -srcstorepass "$pass" -srcalias client \
  -destkeystore "$here/two-entries.p12" -deststoretype PKCS12 -deststorepass "$pass" -destalias server-only -noprompt >/dev/null 2>&1
"$keytool" -importkeystore -srckeystore "$here/p256.p12" -srcstoretype PKCS12 -srcstorepass "$pass" -srcalias client \
  -destkeystore "$here/two-entries.p12" -deststoretype PKCS12 -deststorepass "$pass" -destalias client -noprompt >/dev/null 2>&1

# An EC key the JDK made and keytool kept, signed by the root.
rm -f "$here/keytool-ec.p12"
"$keytool" -genkeypair -keyalg EC -groupname secp256r1 -alias client -dname "CN=keytool-ec" -validity "$days" \
  -keystore "$here/keytool-ec.p12" -storetype PKCS12 -storepass "$pass" >/dev/null 2>&1
"$keytool" -certreq -alias client -keystore "$here/keytool-ec.p12" -storepass "$pass" -file keytool-ec.csr >/dev/null 2>&1
printf 'extendedKeyUsage=clientAuth\n' > keytool-ec.ext
openssl x509 -req -in keytool-ec.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out keytool-ec.pem -days "$days" -extfile keytool-ec.ext 2>/dev/null
cat keytool-ec.pem ca.pem > keytool-ec-chain.pem
"$keytool" -importcert -alias client -keystore "$here/keytool-ec.p12" -storepass "$pass" -file keytool-ec-chain.pem -noprompt >/dev/null 2>&1

# The test servers' own certificate, for localhost, 127.0.0.1 and ::1.
openssl req -x509 -newkey rsa:2048 -nodes -keyout server-key.pem -out server.pem -days "$days" -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1" -addext "extendedKeyUsage=serverAuth" 2>/dev/null
openssl pkcs12 -export -inkey server-key.pem -in server.pem -name server -passout "pass:$pass" -out "$here/server.p12"

printf 'This is not a certificate file.\n' > "$here/not-a-certificate.p12"
echo "made $(ls "$here" | grep -c '\.p12$') files in $here"
