#!/usr/bin/env python3
"""App Store Connect, signing and checks for the Octo iPhone app's store builds, on Linux.

No secret is in this file or in the repository. The App Store Connect key and the Apple
Distribution identity belong to the team and are kept by StreamNook's tooling in /root/.sn-asc;
Octo's own state is in /root/.octo-asc (mode 700). Nothing here prints a secret in any form.

  octo_asc.py setup-ids [--apply]               register the three bundle ids and capabilities
  octo_asc.py setup-profiles [--apply]          one App Store profile per bundle
  octo_asc.py refresh-profiles [--apply]        new profiles, then the old ones deleted
  octo_asc.py setup-testflight [--apply]        the Team and Public groups
  octo_asc.py reserve --ref SHA                 the next build number, for that commit
  octo_asc.py set-review --requires SHA --demo-host HOST    what the beta review build must contain
  octo_asc.py mark-checked N                    build N passed the device checks
  octo_asc.py prepare --tree DIR --unsigned IPA --out IPA      the signed App Store IPA
  octo_asc.py sideload --tree DIR --unsigned IPA --out IPA [--build N]
                                                the IPA for sideloading (--build: no TestFlight)
  octo_asc.py validate --tree DIR --ipa IPA [--sideload]
  octo_asc.py record-built --signed IPA --sideload IPA
  octo_asc.py upload IPA [--wait]
  octo_asc.py publish whats-new --build N --file F
  octo_asc.py publish external --build N
  octo_asc.py publish check --build N
  octo_asc.py publish open-link
  octo_asc.py publish resubmit --build N
  octo_asc.py status [--brief]
  octo_asc.py entitlements IPA                  each bundle's sealed entitlements, as JSON
"""
import argparse
import base64
import contextlib
import copy
import datetime
import fcntl
import hashlib
import json
import os
import plistlib
import re
import secrets
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import store_inputs  # noqa: E402

TEAM = "C84UQRPAQ2"
APP_ID = "app.winters.octo"
GROUP = "group.app.winters.octo"
# Every bundle the app ships that has an executable: where it sits in the .app, its own
# entitlements file in ios/, its profile file in HOME, its capabilities, and the Swift modules
# whose App Intents its metadata must list.
BUNDLES = [
    {"id": APP_ID, "name": "Octo", "exe": "Octo", "path": "", "entitlements": "Octo.entitlements",
     "profile": "app.mobileprovision", "caps": ["APP_GROUPS", "SIRIKIT"], "intents": ["OctoIntents", "Octo"]},
    {"id": APP_ID + ".share", "name": "Octo Share", "exe": "OctoShare", "path": "PlugIns/OctoShare.appex",
     "entitlements": "OctoShare.entitlements", "profile": "share.mobileprovision", "caps": ["APP_GROUPS"],
     "intents": []},
    {"id": APP_ID + ".widgets", "name": "Octo Widgets", "exe": "OctoWidgets", "path": "PlugIns/OctoWidgets.appex",
     "entitlements": "OctoWidgets.entitlements", "profile": "widgets.mobileprovision", "caps": ["APP_GROUPS"],
     "intents": ["OctoIntents", "OctoWidgets"]},
]
SOURCE_KEY = "OctoInstallSource"

HOME = Path("/root/.octo-asc")
WORK = Path("/root/octo-testflight")
REPO = Path("/mnt/c/OctoPlayer")
API = "https://api.appstoreconnect.apple.com"
SDK_BUNDLE = Path("/root/.swiftpm/swift-sdks/darwin.artifactbundle")
TOOLS = Path("/root/.local/share/swiftly/bin")
ZSIGN = Path("/root/octo-tools/bin/zsign-octo")
STREAMNOOK_CONFIG = Path("/root/.sn-asc/config.json")
WHATS_NEW_LIMIT = 4000
EXPIRY_WARN_DAYS = 21
APPROVED_STATES = {"BETA_APPROVED", "READY_FOR_BETA_TESTING", "IN_BETA_TESTING"}
SUBMITTED_STATES = {"WAITING_FOR_BETA_REVIEW", "IN_BETA_REVIEW"} | APPROVED_STATES
APPLE_HAS_UPLOAD = {"PROCESSING", "COMPLETE"}


def seconds(name: str, default: float) -> float:
    """A wait or time limit, settable as OCTO_ASC_<NAME>_SECONDS so tests run in seconds."""
    return float(os.environ.get(f"OCTO_ASC_{name}_SECONDS", default))


class Refused(Exception):
    """A check or a step that cannot go on; the message is what to fix."""


def refuse(message: str) -> None:
    raise Refused(message)


# ---------------------------------------------------------------- secrets never shown

_SECRETS: set[str] = set()
_HIDDEN_FIELDS = re.compile(r'("(?:demoAccount\w*|contact\w*|feedbackEmail)"\s*:\s*)"(?:[^"\\]|\\.)*"')


def keep_secret(value: str) -> None:
    """Every form a secret could be printed in is hidden from then on."""
    if not value:
        return
    raw = value.encode("utf-8")
    for form in (value, raw.hex(), raw.hex().upper(), "enc:" + raw.hex(), base64.b64encode(raw).decode(),
                 base64.urlsafe_b64encode(raw).decode(), urllib.parse.quote(value, safe=""),
                 urllib.parse.quote_plus(value), json.dumps(value)[1:-1], json.dumps(value, ensure_ascii=False)[1:-1]):
        if len(form) >= 3:
            _SECRETS.add(form)


def redact(text: str) -> str:
    text = _HIDDEN_FIELDS.sub(r'\1"(hidden)"', str(text))
    for secret in sorted(_SECRETS, key=len, reverse=True):
        text = text.replace(secret, "(hidden)")
    return text


def say(*parts) -> None:
    print(redact(" ".join(str(p) for p in parts)), flush=True)


# ---------------------------------------------------------------- state files

def save_json(path: Path, data) -> None:
    """Written whole or not at all: a temporary file, then a rename."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def load_json(path: Path, default=None):
    if not path.exists():
        return copy.deepcopy(default)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


@contextlib.contextmanager
def run_lock():
    HOME.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(HOME / "run.lock", "w") as fd:
        fcntl.flock(fd, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)


def load_config() -> dict:
    cfg = load_json(HOME / "config.json")
    if cfg is None:
        refuse(f"{HOME}/config.json is missing; run setup-ids first")
    return cfg


def team_credentials(cfg: dict) -> dict:
    """The team's key and distribution identity, read from StreamNook's config on every run."""
    sn_path = Path(cfg.get("streamnook_config", str(STREAMNOOK_CONFIG)))
    sn = load_json(sn_path)
    if not sn:
        refuse(f"{sn_path} is missing: the team key and certificate live there")
    for key in ("key_id", "issuer_id", "key_path", "certificate_id"):
        if not sn.get(key):
            refuse(f"{sn_path} lacks {key}")
    folder = sn_path.parent
    return {"key_id": sn["key_id"], "issuer_id": sn["issuer_id"], "key_path": sn["key_path"],
            "certificate_id": sn["certificate_id"], "dist_key": str(folder / "dist.key"),
            "dist_cert": str(folder / "dist.pem")}


# ---------------------------------------------------------------- App Store Connect

class Api:
    def __init__(self, creds: dict):
        self.creds = creds
        self._token = (None, 0)

    def token(self) -> str:
        import jwt  # pyjwt, with cryptography for ES256
        now = int(time.time())
        if self._token[0] and self._token[1] - 60 > now:
            return self._token[0]
        with open(self.creds["key_path"], "rb") as f:
            key = f.read()
        exp = now + 15 * 60
        value = jwt.encode({"iss": self.creds["issuer_id"], "iat": now, "exp": exp, "aud": "appstoreconnect-v1"},
                           key, algorithm="ES256", headers={"kid": self.creds["key_id"], "typ": "JWT"})
        self._token = (value, exp)
        return value

    def call(self, method: str, path: str, body=None, params=None, ok=(200, 201, 204)) -> dict:
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token())
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=seconds("HTTP", 120)) as r:
                raw, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        except urllib.error.URLError as e:
            refuse(f"{method} {path}: no answer ({redact(str(e.reason))})")
        if status not in ok:
            try:
                errs = json.loads(raw).get("errors", [])
                detail = "; ".join(f"{x.get('code')}: {x.get('detail') or x.get('title')}" for x in errs)
            except (ValueError, AttributeError):
                detail = raw[:300].decode(errors="replace")
            refuse(f"{method} {path} answered {status}: {redact(detail)}")
        return json.loads(raw) if raw else {}


def every(api, path: str, params: dict) -> list:
    """Every item of a list App Store Connect pages through."""
    items, page = [], api.call("GET", path, params={**params, "limit": 200})
    while True:
        items += page.get("data", [])
        nxt = (page.get("links") or {}).get("next")
        if not nxt:
            return items
        query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(nxt).query))
        page = api.call("GET", path, params=query)


def find_bundle(api, identifier: str):
    res = api.call("GET", "/v1/bundleIds", params={"filter[identifier]": identifier, "filter[platform]": "IOS",
                                                  "include": "bundleIdCapabilities", "limit": 50})
    caps = {c["id"]: c["attributes"].get("capabilityType") for c in res.get("included", [])
            if c.get("type") == "bundleIdCapabilities"}
    for b in res.get("data", []):
        if b["attributes"]["identifier"] == identifier:
            rel = (b.get("relationships", {}).get("bundleIdCapabilities", {}) or {}).get("data") or []
            return b, {caps.get(r["id"]) for r in rel}
    return None, set()


def find_app(api):
    res = api.call("GET", "/v1/apps", params={"filter[bundleId]": APP_ID, "limit": 10})
    for a in res.get("data", []):
        if a["attributes"]["bundleId"] == APP_ID:
            return a
    return None


def cmd_setup_ids(api, apply: bool) -> None:
    HOME.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(HOME, 0o700)
    cfg = load_json(HOME / "config.json", {"streamnook_config": str(STREAMNOOK_CONFIG), "team_id": TEAM})
    cfg.setdefault("bundles", {})
    for b in BUNDLES:
        found, caps = find_bundle(api, b["id"])
        if found is None:
            say(("registering" if apply else "would register"), b["id"], f"({b['name']})")
            if apply:
                found = api.call("POST", "/v1/bundleIds", body={"data": {"type": "bundleIds", "attributes": {
                    "identifier": b["id"], "name": b["name"], "platform": "IOS"}}})["data"]
        else:
            say(b["id"], "is registered")
        for cap in b["caps"]:
            if cap in caps:
                continue
            say(("enabling" if apply else "would enable"), cap, "on", b["id"])
            if apply:
                api.call("POST", "/v1/bundleIdCapabilities", body={"data": {
                    "type": "bundleIdCapabilities", "attributes": {"capabilityType": cap},
                    "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": found["id"]}}}}})
        if apply and found is not None:
            cfg["bundles"][b["id"]] = found["id"]
    if apply:
        save_json(HOME / "config.json", cfg)
        say("next: create the App Group", GROUP, "in the developer portal and tick it on all three App IDs")
    elif not (HOME / "config.json").exists():
        save_json(HOME / "config.json", cfg)


# ---------------------------------------------------------------- profiles

def profile_plist(raw: bytes) -> dict:
    start, end = raw.find(b"<?xml"), raw.find(b"</plist>")
    if start < 0 or end < 0:
        return {}
    return plistlib.loads(raw[start:end + len(b"</plist>")])


def profile_problems(bundle: dict, raw: bytes) -> list[str]:
    """Why a profile cannot sign this bundle for the App Store, if it cannot."""
    prof = profile_plist(raw)
    ent = prof.get("Entitlements", {})
    problems = []
    if prof.get("ProvisionedDevices") or prof.get("ProvisionsAllDevices"):
        problems.append(f"{bundle['id']}: the profile is not an App Store profile")
    if ent.get("application-identifier") != f"{TEAM}.{bundle['id']}":
        problems.append(f"{bundle['id']}: the profile is for {ent.get('application-identifier')}")
    if ent.get("get-task-allow"):
        problems.append(f"{bundle['id']}: the profile grants get-task-allow")
    if GROUP not in (ent.get("com.apple.security.application-groups") or []):
        problems.append(f"{bundle['id']}: the profile lacks the App Group {GROUP}; tick it on this App ID")
    if "SIRIKIT" in bundle["caps"] and not ent.get("com.apple.developer.siri"):
        problems.append(f"{bundle['id']}: the profile lacks Siri")
    return problems


def write_profile(name: str, content: bytes) -> None:
    path = HOME / name
    tmp = path.with_name(name + ".tmp")
    tmp.write_bytes(content)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def create_profile(api, cfg: dict, creds: dict, bundle: dict) -> dict:
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S")
    res = api.call("POST", "/v1/profiles", body={"data": {
        "type": "profiles",
        "attributes": {"name": f"Octo {bundle['id']} App Store {stamp}", "profileType": "IOS_APP_STORE"},
        "relationships": {
            "bundleId": {"data": {"type": "bundleIds", "id": cfg["bundles"][bundle["id"]]}},
            "certificates": {"data": [{"type": "certificates", "id": creds["certificate_id"]}]}}}})
    return res["data"]


def active_profile(api, profile_id: str):
    res = api.call("GET", f"/v1/profiles/{profile_id}", ok=(200, 404))
    data = res.get("data")
    if not data or data["attributes"].get("profileState") != "ACTIVE":
        return None
    return data


def setup_profiles(api, apply: bool, refresh: bool) -> None:
    cfg = load_config()
    creds = team_credentials(cfg)
    if not cfg.get("bundles") or any(b["id"] not in cfg["bundles"] for b in BUNDLES):
        refuse("the bundle ids are not all registered; run setup-ids --apply first")
    app = find_app(api)
    if app is None:
        refuse(f"there is no App Store Connect app for {APP_ID} yet; make it on the website (New App)")
    cfg["app_id"] = app["id"]
    cfg.setdefault("profiles", {})
    for b in BUNDLES:
        old = cfg["profiles"].get(b["id"])
        if old and not refresh:
            data = active_profile(api, old["id"])
            local = HOME / b["profile"]
            if data and local.exists() and base64.b64decode(data["attributes"]["profileContent"]) == local.read_bytes() \
                    and not profile_problems(b, local.read_bytes()):
                say(b["id"], "keeps profile", old["id"])
                continue
        say(("making" if apply else "would make"), "an App Store profile for", b["id"])
        if not apply:
            continue
        data = create_profile(api, cfg, creds, b)
        content = base64.b64decode(data["attributes"]["profileContent"])
        problems = profile_problems(b, content)
        if problems:
            say("deleting the new profile", data["id"], "it cannot sign", b["id"])
            api.call("DELETE", f"/v1/profiles/{data['id']}", ok=(204, 404))
            refuse("; ".join(problems))
        write_profile(b["profile"], content)
        cfg["profiles"][b["id"]] = {"id": data["id"], "expires": data["attributes"].get("expirationDate")}
        save_json(HOME / "config.json", cfg)
        say(b["id"], "profile", data["id"], "expires", data["attributes"].get("expirationDate"))
        if refresh and old and old["id"] != data["id"]:
            api.call("DELETE", f"/v1/profiles/{old['id']}", ok=(204, 404))
            say("deleted the old profile", old["id"])
    if apply:
        save_json(HOME / "config.json", cfg)


def cmd_setup_testflight(api, apply: bool) -> None:
    cfg = load_config()
    if not cfg.get("app_id"):
        refuse("no app id; run setup-profiles --apply first")
    groups = api.call("GET", f"/v1/apps/{cfg['app_id']}/betaGroups", params={"limit": 50}).get("data", [])
    by_name = {g["attributes"]["name"]: g for g in groups}
    cfg.setdefault("groups", {})
    for name, internal in (("Team", True), ("Public", False)):
        group = by_name.get(name)
        if group is None:
            say(("making" if apply else "would make"), "the", "internal" if internal else "external", "group", name)
            if not apply:
                continue
            attrs = {"name": name, "isInternalGroup": internal, "feedbackEnabled": True}
            if internal:
                attrs["hasAccessToAllBuilds"] = True
            else:
                attrs.update({"publicLinkEnabled": False, "publicLinkLimitEnabled": False})
            group = api.call("POST", "/v1/betaGroups", body={"data": {"type": "betaGroups", "attributes": attrs,
                "relationships": {"app": {"data": {"type": "apps", "id": cfg["app_id"]}}}}})["data"]
        a = group["attributes"]
        if a.get("iosBuildsAvailableForAppleSiliconMac") is not False or a.get("iosBuildsAvailableForAppleVision") is not False:
            say(("closing" if apply else "would close"), name, "to Macs and Vision Pro")
            if apply:
                api.call("PATCH", f"/v1/betaGroups/{group['id']}", body={"data": {"type": "betaGroups", "id": group["id"],
                    "attributes": {"iosBuildsAvailableForAppleSiliconMac": False, "iosBuildsAvailableForAppleVision": False}}})
        if apply:
            cfg["groups"][name] = group["id"]
    if apply:
        save_json(HOME / "config.json", cfg)


# ---------------------------------------------------------------- build numbers

def int_of(value) -> int:
    try:
        return int(str(value).split(".")[0])
    except ValueError:
        return 0


def git(*args: str) -> str:
    return subprocess.run(["git", "-c", "safe.directory=*", "-C", str(REPO), *args], check=True,
                          capture_output=True, text=True).stdout.strip()


def apple_has(api, cfg: dict, build: int) -> bool:
    """Whether App Store Connect holds build N: a build record, or an upload that did not fail."""
    if every(api, "/v1/builds", {"filter[app]": cfg["app_id"], "filter[version]": str(build)}):
        return True
    return any(int_of(u["attributes"].get("cfBundleVersion")) == build
               and ((u["attributes"].get("state") or {}).get("state")) in APPLE_HAS_UPLOAD
               for u in every(api, f"/v1/apps/{cfg['app_id']}/buildUploads", {}))


APP_TAG = re.compile(r"(android|desktop|ios)-v(\d+(?:\.\d+)*)")
APP_TAG_GLOBS = ("android-v*", "desktop-v*", "ios-v*")


def version_key(version: str) -> list[int]:
    return [int(x) for x in version.split(".")]


def release_floor(tags: list[str]) -> list[int] | None:
    """The lowest version a store build may read: the newest major.minor among the Android and
    desktop releases (their patch releases are theirs alone), or a newer iPhone release in full."""
    floors = [version_key(m.group(2)) if m.group(1) == "ios" else version_key(m.group(2))[:2]
              for m in map(APP_TAG.fullmatch, tags) if m]
    return max(floors, default=None)


RELEASE_TAG_REF = re.compile(r"refs/tags/(?:android|desktop|ios)-v[^/^{}]+")


def tags_are_current() -> None:
    """The local release tags are exactly the ones origin has, both ways, so the floor below is
    never read from a stale, moved or unpushed tag."""
    remote = {}
    for line in git("ls-remote", "--tags", "origin", *APP_TAG_GLOBS).splitlines():
        sha, ref = line.split()
        if RELEASE_TAG_REF.fullmatch(ref):
            remote[ref] = sha
    local = {ref: sha for sha, ref in (line.split() for line in git(
        "for-each-ref", "--format=%(objectname) %(refname)", *(f"refs/tags/{g}" for g in APP_TAG_GLOBS)).splitlines())
        if RELEASE_TAG_REF.fullmatch(ref)}
    name = lambda ref: ref.removeprefix("refs/tags/")
    missing = sorted(name(r) for r in remote.keys() - local.keys())
    unpushed = sorted(name(r) for r in local.keys() - remote.keys())
    moved = sorted(name(r) for r in local.keys() & remote.keys() if local[r] != remote[r])
    problems = ([f"not fetched: {', '.join(missing)} (git fetch origin --tags)"] if missing else []) + \
               ([f"only here: {', '.join(unpushed)} (push or delete them)"] if unpushed else []) + \
               ([f"moved on origin: {', '.join(moved)} (check which is right, then git fetch origin --tags --force)"]
                if moved else [])
    if problems:
        refuse("the local release tags differ from origin's; " + "; ".join(problems))


def release_versions(ref: str) -> tuple[str, list[int] | None]:
    """The version the three iPhone plists at ref read, and the release floor of the tags
    reachable from ref."""
    versions = {plistlib.loads(git("show", f"{ref}:ios/{name}").encode())["CFBundleShortVersionString"]
                for name in ("Info.plist", "OctoShare-Info.plist", "OctoWidgets-Info.plist")}
    if len(versions) != 1:
        refuse(f"the iPhone plists at {ref[:8]} name different versions: {sorted(versions)}")
    tags_are_current()
    return versions.pop(), release_floor(git("tag", "--merged", ref, "--list", *APP_TAG_GLOBS).split())


def check_release_version(iphone: str, floor: list[int] | None) -> None:
    """A store build never reads older than the apps release its commit carries: What's new and
    the sideload updater both go by the version."""
    if not re.fullmatch(r"\d+(\.\d+)*", iphone):
        refuse(f"the iPhone plists read {iphone!r}, which is not a version")
    if floor is None:
        refuse("no android-v, desktop-v or ios-v release tag is reachable from this commit")
    if version_key(iphone) < floor:
        refuse(f"the iPhone plists read {iphone}, older than the release {'.'.join(map(str, floor))} this commit "
               "carries; set the three Info.plist versions to the apps version and rewrite What's new")


def cmd_reserve(api, ref: str) -> int:
    if not re.fullmatch(r"[0-9a-f]{40}", ref):
        refuse("--ref takes a full 40-character commit id")
    check_release_version(*release_versions(ref))
    cfg = load_config()
    with run_lock():
        open_one = load_json(HOME / "reserved.json")
        if open_one:
            # An upload cut off after it reached Apple is recorded, never burned.
            if open_one.get("state") == "uploading" and apple_has(api, cfg, open_one["build"]):
                record_upload(open_one, open_one.get("upload_id"))
                say("build", open_one["build"], "reached Apple before its upload was cut off; recorded it")
            else:
                close_reservation(open_one, burned=True)
                say("closed the open reservation of build", open_one["build"], "(its number is not reused)")
        best = max(load_json(HOME / "burned.json", []), default=0)
        builds = every(api, "/v1/builds", {"filter[app]": cfg["app_id"], "fields[builds]": "version"})
        best = max([best] + [int_of(b["attributes"].get("version")) for b in builds])
        ups = every(api, f"/v1/apps/{cfg['app_id']}/buildUploads", {})
        best = max([best] + [int_of(u["attributes"].get("cfBundleVersion")) for u in ups])
        best = max([best, int_of((HOME / "last-build").read_text().strip()) if (HOME / "last-build").exists() else 0]
                   + [u["build"] for u in load_json(HOME / "uploads.json", [])])
        profiles = {}
        for b in BUNDLES:
            entry = (cfg.get("profiles") or {}).get(b["id"])
            data = active_profile(api, entry["id"]) if entry else None
            local = HOME / b["profile"]
            if not data or not local.exists():
                refuse(f"{b['id']}: no ACTIVE profile; run refresh-profiles")
            if base64.b64decode(data["attributes"]["profileContent"]) != local.read_bytes():
                refuse(f"{b['id']}: the local profile is not the one App Store Connect holds; run refresh-profiles")
            profiles[b["id"]] = {"id": entry["id"], "sha256": hashlib.sha256(local.read_bytes()).hexdigest()}
        tree = git("rev-parse", f"{ref}:ios")
        reservation = {"build": best + 1, "commit": ref, "tree": tree, "profiles": profiles, "state": "open",
                       "nonce": secrets.token_hex(8),
                       "created": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
        save_json(HOME / "reserved.json", reservation)
    say("reserved build", best + 1, "for", ref)
    return best + 1


def reservation() -> dict:
    found = load_json(HOME / "reserved.json")
    if not found:
        refuse("no open reservation; run reserve --ref <commit>")
    return found


# ---------------------------------------------------------------- the bundle

def sdk_dt_keys() -> dict:
    """The keys Xcode stamps into every bundle, resolved from the installed Darwin SDK."""
    plat = SDK_BUNDLE / "Developer/Platforms/iPhoneOS.platform"
    sdks = plat / "Developer/SDKs"
    sdk = sorted((d for d in os.listdir(sdks) if re.fullmatch(r"iPhoneOS\d+(\.\d+)*\.sdk", d)),
                 key=lambda d: [int(x) for x in re.findall(r"\d+", d)])[-1]
    settings = plistlib.load(open(sdks / sdk / "SDKSettings.plist", "rb"))
    system = plistlib.load(open(sdks / sdk / "System/Library/CoreServices/SystemVersion.plist", "rb"))
    xcode = plistlib.load(open(SDK_BUNDLE / "Xcode.app/Contents/version.plist", "rb"))
    parts = [int(x) for x in xcode["CFBundleShortVersionString"].split(".")] + [0, 0]
    return {
        "DTCompiler": "com.apple.compilers.llvm.clang.1_0",
        "DTPlatformBuild": system["ProductBuildVersion"],
        "DTPlatformName": "iphoneos",
        "DTPlatformVersion": settings["Version"],
        "DTSDKBuild": system["ProductBuildVersion"],
        "DTSDKName": settings["CanonicalName"],
        "DTXcode": f"{parts[0]:02d}{parts[1]}{parts[2]}",
        "DTXcodeBuild": xcode["ProductBuildVersion"],
    }


# Required-reason API categories, by the symbols and strings that reveal them, with the reason
# a music player has for each.
REASONS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": ("C617.1", {
        "_stat", "_fstat", "_lstat", "_fstatat", "_getattrlist", "_fgetattrlist", "_getattrlistat",
        "_getattrlistbulk", "_stat$INODE64", "_fstat$INODE64", "_lstat$INODE64"},
        [b"NSFileModificationDate", b"contentModificationDateKey", b"creationDateKey"]),
    "NSPrivacyAccessedAPICategorySystemBootTime": ("35F9.1", {"_mach_absolute_time"}, [b"systemUptime"]),
    "NSPrivacyAccessedAPICategoryDiskSpace": ("E174.1", {
        "_statfs", "_statvfs", "_fstatfs", "_fstatvfs", "_statfs$INODE64", "_fstatfs$INODE64"},
        [b"volumeAvailableCapacity", b"NSFileSystemFreeSize"]),
    "NSPrivacyAccessedAPICategoryUserDefaults": ("CA92.1", {"_OBJC_CLASS_$_NSUserDefaults"}, [b"NSUserDefaults"]),
}


def run_tool(name: str, *args: str) -> str:
    out = subprocess.run([str(TOOLS / name), *args], capture_output=True, text=True)
    if out.returncode != 0:
        refuse(f"{name} failed: {out.stderr[:300]}")
    return out.stdout


def undefined_symbols(binary: Path) -> set[str]:
    return set(run_tool("llvm-nm", "-u", "--just-symbol-name", str(binary)).split())


def defined_symbols(binary: Path) -> set[str]:
    return set(run_tool("llvm-nm", "--defined-only", "--just-symbol-name", str(binary)).split())


def scanned_categories(binary: Path, symbols=undefined_symbols) -> set[str]:
    syms = symbols(binary)
    blob = binary.read_bytes()
    if b"activeInputModes" in blob:
        refuse(f"{binary.name} reads the active keyboards: declare a reason for it by hand")
    return {cat for cat, (_, names, hints) in REASONS.items() if syms & names or any(h in blob for h in hints)}


def privacy_manifest(base: dict, categories: set[str]) -> dict:
    """The tree's manifest, with any scanned category it lacks added with its reason."""
    manifest = copy.deepcopy(base)
    types = manifest.setdefault("NSPrivacyAccessedAPITypes", [])
    have = {t.get("NSPrivacyAccessedAPIType") for t in types}
    for cat in sorted(categories - have):
        types.append({"NSPrivacyAccessedAPIType": cat, "NSPrivacyAccessedAPITypeReasons": [REASONS[cat][0]]})
    return manifest


def expected_entitlements(tree: Path, bundle: dict, store: bool) -> dict:
    """What the bundle is sealed with: its own entitlements file, and for the App Store the four
    keys Xcode adds. No keychain-access-groups, so the default Keychain group is the app's id."""
    with open(tree / "ios" / bundle["entitlements"], "rb") as f:
        own = plistlib.load(f)
    if not store:
        return own
    return {**own, "application-identifier": f"{TEAM}.{bundle['id']}",
            "com.apple.developer.team-identifier": TEAM, "get-task-allow": False, "beta-reports-active": True}


def granted(value, allowed) -> bool:
    """Whether a profile entitlement value allows a sealed value (wildcards like TEAM.* honored)."""
    if isinstance(value, list):
        return isinstance(allowed, list) and all(any(granted(v, a) for a in allowed) for v in value)
    if isinstance(value, bool):
        return allowed is True or allowed == value
    if isinstance(value, str) and isinstance(allowed, str):
        return value == allowed or allowed == "*" or (allowed.endswith(".*") and value.startswith(allowed[:-1]))
    return value == allowed


def fit_problems(expected: dict, profile_entitlements: dict, bundle_id: str) -> list[str]:
    problems = []
    for key, value in expected.items():
        if key not in profile_entitlements:
            problems.append(f"{bundle_id}: its profile does not grant {key}")
        elif not granted(value, profile_entitlements[key]):
            problems.append(f"{bundle_id}: its profile does not grant {key} = {value!r}")
    return problems


def tree_version(tree: Path) -> str:
    versions = {}
    for name in ("Info.plist", "OctoShare-Info.plist", "OctoWidgets-Info.plist"):
        with open(tree / "ios" / name, "rb") as f:
            versions[name] = plistlib.load(f)["CFBundleShortVersionString"]
    if len(set(versions.values())) != 1:
        refuse(f"the plists name different versions: {versions}")
    return versions["Info.plist"]


def info_plists(app: Path) -> list[Path]:
    return sorted(app.rglob("Info.plist"))


def bundle_dir(app: Path, bundle: dict) -> Path:
    return app / bundle["path"] if bundle["path"] else app


def stage(tree: Path, unsigned: Path, build: int, work: Path, sideload: bool) -> Path:
    """The unsigned app, stamped and filled the way Xcode would ship it, ready to sign."""
    with zipfile.ZipFile(unsigned) as z:
        z.extractall(work)
    apps = list((work / "Payload").glob("*.app"))
    if len(apps) != 1:
        refuse(f"expected one .app in {unsigned.name}, found {len(apps)}")
    app = apps[0]
    ios = tree / "ios"
    version, dt = tree_version(tree), sdk_dt_keys()
    for b in BUNDLES:
        if not (bundle_dir(app, b) / "Info.plist").exists():
            refuse(f"{unsigned.name} has no {b['exe']} bundle")
    for path in app.rglob("_CodeSignature"):
        shutil.rmtree(path, ignore_errors=True)
    for path in app.rglob("embedded.mobileprovision"):
        path.unlink()
    executables = {bundle_dir(app, b).resolve() for b in BUNDLES}
    for plist in info_plists(app):
        with open(plist, "rb") as f:
            info = plistlib.load(f)
        info.update(dt)
        if plist.parent.resolve() in executables:
            info["CFBundleShortVersionString"] = version
            info["CFBundleVersion"] = str(build)
            info["UIDeviceFamily"] = [1]
            if plist.parent.resolve() != app.resolve():
                info["CFBundleSupportedPlatforms"] = ["iPhoneOS"]
        with open(plist, "wb") as f:
            plistlib.dump(info, f, fmt=plistlib.FMT_XML)
    # The compiled icon catalog replaces xtool's loose PNG.
    icon = ios / "Resources/StoreAssets/Icon"
    with open(app / "Info.plist", "rb") as f:
        info = plistlib.load(f)
    info.pop("CFBundleIconFile", None)
    (app / "AppIcon.png").unlink(missing_ok=True)
    with open(icon / "partial-Info.plist", "rb") as f:
        info.update(plistlib.load(f))
    nested = ((info.get("CFBundleIcons") or {}).get("CFBundlePrimaryIcon") or {}).get("CFBundleIconName")
    if nested and not info.get("CFBundleIconName"):
        info["CFBundleIconName"] = nested
    if sideload:
        info[SOURCE_KEY] = "sideload"
    else:
        info.pop(SOURCE_KEY, None)
    with open(app / "Info.plist", "wb") as f:
        plistlib.dump(info, f, fmt=plistlib.FMT_XML)
    for name in os.listdir(icon):
        if name == "Assets.car" or (name.startswith("AppIcon") and name.endswith(".png")):
            shutil.copy2(icon / name, app / name)
    for exe, where in store_inputs.EXPECTED_METADATA.items():
        source = ios / "Resources/StoreAssets/Intents" / exe / store_inputs.METADATA
        target = (app / where / store_inputs.METADATA)
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
    with open(ios / "PrivacyInfo.xcprivacy", "rb") as f:
        base = plistlib.load(f)
    for b in BUNDLES:
        folder = bundle_dir(app, b)
        manifest = privacy_manifest(base, scanned_categories(folder / b["exe"]))
        with open(folder / "PrivacyInfo.xcprivacy", "wb") as f:
            plistlib.dump(manifest, f, fmt=plistlib.FMT_XML)
    return app


def zip_app(app: Path, out: Path) -> None:
    payload = app.parent
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(payload.rglob("*")):
            z.write(path, path.relative_to(payload.parent).as_posix())


def zsign(args: list[str], work: Path) -> None:
    out = subprocess.run([str(ZSIGN), *args], cwd=work, capture_output=True, text=True)
    if out.returncode != 0:
        refuse("zsign-octo failed: " + redact((out.stdout + out.stderr)[-1500:]))


def unsigned_from(tree: Path) -> None:
    res = reservation()
    from_file = (WORK / "unsigned.from").read_text().split()
    if from_file[0] != res["commit"] or tree.name != res["commit"]:
        refuse(f"the unsigned build is of {from_file[0]}, the reservation is for {res['commit']}")


def cmd_prepare(tree: Path, unsigned: Path, out: Path) -> None:
    res = reservation()
    unsigned_from(tree)
    creds = team_credentials(load_config())
    for b in BUNDLES:
        local = HOME / b["profile"]
        if hashlib.sha256(local.read_bytes()).hexdigest() != res["profiles"][b["id"]]["sha256"]:
            refuse(f"{b['id']}: the profile changed since reserve; reserve again")
    with tempfile.TemporaryDirectory(prefix="octo-tf-") as temp:
        work = Path(temp)
        app = stage(tree, unsigned, res["build"], work / "x", sideload=False)
        args = ["-k", creds["dist_key"], "-c", creds["dist_cert"]]
        for b in BUNDLES:
            args += ["-m", str(HOME / b["profile"])]
        for b in BUNDLES:
            ent = work / f"{b['id']}.entitlements"
            with open(ent, "wb") as f:
                plistlib.dump(expected_entitlements(tree, b, store=True), f, fmt=plistlib.FMT_XML)
            problems = fit_problems(expected_entitlements(tree, b, store=True),
                                    profile_plist((HOME / b["profile"]).read_bytes()).get("Entitlements", {}), b["id"])
            if problems:
                refuse("; ".join(problems))
            args += ["--bundle-entitlements", f"{b['id']}={ent}"]
        staged = work / "staged.ipa"
        zip_app(app, staged)
        zsign(args + ["-z", "9", "-o", str(out.resolve()), str(staged)], work)
    say("wrote", out, f"({out.stat().st_size >> 20} MB)")


def built_from(tree: Path) -> None:
    """A sideload-only build has no reservation; its unsigned build must still be of the tree's commit."""
    from_file = (WORK / "unsigned.from").read_text().split()
    if from_file[0] != tree.name:
        refuse(f"the unsigned build is of {from_file[0]}, the tree is {tree.name}")


def cmd_sideload(tree: Path, unsigned: Path, out: Path, build: int = None) -> None:
    """The IPA for sideloading: with no `build`, the open TestFlight reservation's number; with one,
    a sideload-only release, which needs no App Store Connect."""
    if build is None:
        build = reservation()["build"]
        unsigned_from(tree)
    else:
        if build < 1:
            refuse("--build takes a positive build number")
        built_from(tree)
    with tempfile.TemporaryDirectory(prefix="octo-tf-") as temp:
        work = Path(temp)
        app = stage(tree, unsigned, build, work / "x", sideload=True)
        args = ["-a"]
        for b in BUNDLES:
            ent = work / f"{b['id']}.entitlements"
            with open(ent, "wb") as f:
                plistlib.dump(expected_entitlements(tree, b, store=False), f, fmt=plistlib.FMT_XML)
            args += ["--bundle-entitlements", f"{b['id']}={ent}"]
        staged = work / "staged.ipa"
        zip_app(app, staged)
        zsign(args + ["-z", "9", "-o", str(out.resolve()), str(staged)], work)
    say("wrote", out, f"({out.stat().st_size >> 20} MB)")


# ---------------------------------------------------------------- reading a signed bundle

def macho_archs(data: bytes) -> list[int]:
    magic = struct.unpack("<I", data[:4])[0]
    if magic == 0xFEEDFACF:
        return [struct.unpack("<i", data[4:8])[0]]
    if struct.unpack(">I", data[:4])[0] in (0xCAFEBABE, 0xCAFEBABF):
        n = struct.unpack(">I", data[4:8])[0]
        return [struct.unpack(">i", data[8 + i * 20:12 + i * 20])[0] for i in range(n)]
    return []


def code_signature(data: bytes):
    """The code signature superblob offset of a thin 64-bit Mach-O, or None."""
    if len(data) < 32 or struct.unpack("<I", data[:4])[0] != 0xFEEDFACF:
        return None
    ncmds = struct.unpack("<I", data[16:20])[0]
    off = 32
    for _ in range(ncmds):
        cmd, size = struct.unpack("<II", data[off:off + 8])
        if cmd == 0x1D:
            return struct.unpack("<I", data[off + 8:off + 12])[0]
        off += size
    return None


def sealed_entitlements(data: bytes):
    """The entitlements plist sealed in a Mach-O's code signature (blob 0xfade7171), or None."""
    dataoff = code_signature(data)
    if dataoff is None:
        return None
    magic, _length, count = struct.unpack(">III", data[dataoff:dataoff + 12])
    if magic != 0xFADE0CC0:
        return None
    for i in range(count):
        _typ, boff = struct.unpack(">II", data[dataoff + 12 + i * 8:dataoff + 20 + i * 8])
        bmagic, blen = struct.unpack(">II", data[dataoff + boff:dataoff + boff + 8])
        if bmagic == 0xFADE7171:
            return plistlib.loads(data[dataoff + boff + 8:dataoff + boff + blen])
    return None


def cmd_entitlements(ipa: Path) -> None:
    out = {}
    with zipfile.ZipFile(ipa) as z:
        for b, root in ipa_bundles(z):
            info = plistlib.loads(z.read(root + "Info.plist"))
            out[info.get("CFBundleIdentifier")] = sealed_entitlements(z.read(root + info["CFBundleExecutable"])) or {}
    print(json.dumps(out, sort_keys=True))


def ipa_bundles(z: zipfile.ZipFile) -> list[tuple[dict, str]]:
    apps = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("Payload/") and n.split("/")[1].endswith(".app")})
    if len(apps) != 1:
        refuse(f"expected one .app, found {apps}")
    app = f"Payload/{apps[0]}/"
    return [(b, app + (b["path"] + "/" if b["path"] else "")) for b in BUNDLES]


# ---------------------------------------------------------------- App Intents metadata

KNOWN_INTENTS_PROTOCOLS = {
    "AppIntent", "AudioPlaybackIntent", "AudioRecordingIntent", "LiveActivityIntent", "OpenIntent",
    "ForegroundContinuableIntent", "ProgressReportingIntent", "SetValueIntent", "WidgetConfigurationIntent",
    "ControlConfigurationIntent", "URLRepresentableIntent", "DeleteIntent", "AppEntity", "IndexedEntity",
    "TransientAppEntity", "FileEntity", "EntityQuery", "EntityStringQuery", "EntityPropertyQuery",
    "EnumerableEntityQuery", "AppEnum", "AppShortcutsProvider",
}
NOT_LISTED_PROTOCOLS = {"AppIntentsPackage", "DynamicOptionsProvider"}
_INTENT_MACROS = re.compile(r"@App(?:Intent|Entity|Enum)\s*\(")
_TARGET = re.compile(r'\.target\(name:\s*"(\w+)",\s*dependencies:\s*\[([^\]]*)\]')
_DECLARATION = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|fileprivate|open|final|nonisolated)\s+)*"
    r"(struct|enum|class|actor|extension)\s+([A-Za-z_]\w*)\s*(?:<[^>{]*>)?\s*:\s*([^{]+)\{", re.M)


def declared_intents(ios: Path, modules: list[str]) -> set[str]:
    """`Module.Type` for every type the modules declare (or extend) as an App Intents type."""
    found = set()
    for module in modules:
        for path in sorted((ios / "Sources" / module).rglob("*.swift")):
            text = path.read_text(encoding="utf-8")
            if "import AppIntents" not in text:
                continue
            if _INTENT_MACROS.search(text):
                refuse(f"{path.relative_to(ios)} declares App Intents with a macro, which this check cannot read")
            for _kind, name, conformances in _DECLARATION.findall(text):
                names = {c.replace("@retroactive", "").strip().split(".")[-1]
                         for c in conformances.split(" where ")[0].split(",") if c.strip()}
                unknown = {n for n in names - KNOWN_INTENTS_PROTOCOLS - NOT_LISTED_PROTOCOLS
                           if re.search(r"(Intent|Query|Entity|Enum)$", n)}
                if unknown:
                    refuse(f"{path.relative_to(ios)}: {name} conforms to unknown App Intents protocol {sorted(unknown)}; "
                           "add it to KNOWN_INTENTS_PROTOCOLS or NOT_LISTED_PROTOCOLS")
                if names & KNOWN_INTENTS_PROTOCOLS:
                    found.add(f"{module}.{name}")
    return found


METADATA_KINDS = ("actions", "entities", "queries", "enums")


def metadata_entries(doc: dict, kinds=METADATA_KINDS) -> list[dict]:
    """The action, entity, query and enum entries of an extract.actionsdata document (only the
    top-level lists, so a parameter's value type never counts as one of ours)."""
    found = []
    for kind in kinds:
        items = doc.get(kind) or []
        values = items.values() if isinstance(items, dict) else items
        found += [v for v in values if isinstance(v, dict) and v.get("mangledTypeName")]
    return found


def entry_name(entry: dict, demangler) -> str:
    return entry.get("fullyQualifiedTypeName") or demangler(entry["mangledTypeName"])


def demangle(mangled: str) -> str:
    return run_tool("swift-demangle", "--compact", "$s" + mangled).strip()


def package_problems(ios: Path) -> list[str]:
    """Each bundle's App Intents modules are exactly the modules with App Intents that its
    Package.swift target is built from."""
    deps = {name: set(re.findall(r'"(\w+)"', listed)) for name, listed in _TARGET.findall((ios / "Package.swift").read_text())}
    with_intents = {m.name for m in (ios / "Sources").iterdir() if m.is_dir()
                    and any("import AppIntents" in p.read_text(encoding="utf-8") for p in m.rglob("*.swift"))}
    problems = []
    for b in BUNDLES:
        built_from = ({b["exe"]} | deps.get(b["exe"], set())) & with_intents
        if b["exe"] not in deps:
            problems.append(f"Package.swift has no target {b['exe']}")
        elif built_from != set(b["intents"]):
            problems.append(f"{b['id']} is built from App Intents modules {sorted(built_from)}, "
                            f"the release tools expect {sorted(b['intents'])}")
    return problems


def metadata_pairs(doc: dict, own_bundle: str) -> set[tuple[str, str]]:
    """(bundle id, mangled type name) for every type name the metadata gives, the V2 forms too.
    The App Shortcuts provider is always the app's."""
    pairs = set()
    for entry in metadata_entries(doc):
        for key, value in entry.items():
            if not key.startswith("mangledTypeName"):
                continue
            if isinstance(value, str):
                pairs.add((own_bundle, value))
            elif isinstance(value, dict):
                pairs |= {(k, v) for k, v in value.items() if isinstance(v, str)}
    if doc.get("autoShortcutProviderMangledName"):
        pairs.add((APP_ID, doc["autoShortcutProviderMangledName"]))
    return pairs


def metadata_names(doc: dict, demangler=demangle) -> set[str]:
    names = {entry_name(e, demangler) for e in metadata_entries(doc)}
    if doc.get("autoShortcutProviderMangledName"):
        names.add(demangler(doc["autoShortcutProviderMangledName"]))
    return names


def metadata_problems(docs: dict, declared: dict, symbols: dict, demangler=demangle) -> list[str]:
    """docs: bundle id -> parsed metadata; declared: bundle id -> `Module.Type` set; symbols: bundle
    id -> defined symbol set of its final binary."""
    ours = {b["id"] for b in BUNDLES}
    problems = []
    for bundle_id, doc in docs.items():
        for named, mangled in sorted(metadata_pairs(doc, bundle_id)):
            if named not in ours:
                problems.append(f"{bundle_id} metadata names a bundle that is not ours: {named}")
            elif f"_$s{mangled}Mn" not in symbols.get(named, set()):
                problems.append(f"{bundle_id} metadata names {mangled}, which the {named} binary does not define")
        missing = declared.get(bundle_id, set()) - metadata_names(doc, demangler)
        if missing:
            problems.append(f"{bundle_id} metadata lacks {sorted(missing)}")
    return problems


def canary_problems(docs: dict, declared: dict, symbols: dict, demangler=demangle) -> list[str]:
    """The metadata check must notice, for each kind on its own, one of our types renamed and one
    of them dropped."""
    problems = []
    for bundle_id, doc in docs.items():
        for kind in ("actions", "entities", "queries"):
            items = doc.get(kind) or []
            keys = list(items) if isinstance(items, dict) else list(range(len(items)))
            ours = [k for k in keys if isinstance(items[k], dict) and items[k].get("mangledTypeName")
                    and entry_name(items[k], demangler) in declared.get(bundle_id, set())]
            if not ours:
                continue
            renamed = copy.deepcopy(doc)
            entry = renamed[kind][ours[0]]
            for key, value in list(entry.items()):
                if key.startswith("mangledTypeName"):
                    entry[key] = value + "X" if isinstance(value, str) else {k: v + "X" for k, v in value.items()}
            if not metadata_problems({bundle_id: renamed}, declared, symbols, demangler):
                problems.append(f"canary: the check missed a renamed {kind} entry in {bundle_id}")
            dropped = copy.deepcopy(doc)
            if isinstance(items, dict):
                dropped[kind].pop(ours[0])
            else:
                dropped[kind] = [v for i, v in enumerate(items) if i != ours[0]]
            if not metadata_problems({bundle_id: dropped}, declared, symbols, demangler):
                problems.append(f"canary: the check missed a dropped {kind} entry in {bundle_id}")
    return problems


# ---------------------------------------------------------------- validate

CR_SUFFIXES = (".swift", ".plist", ".yml", ".json", ".entitlements")
# The folders a build writes into the tree it builds; nothing in them is source.
BUILT_FOLDERS = ("ios/.build/", "ios/xtool/")


def cr_problems(tree: Path) -> list[str]:
    roots = [tree / "ios", tree / store_inputs.WORKFLOW]
    bad = []
    for root in roots:
        for path in ([root] if root.is_file() else root.rglob("*")):
            name = path.relative_to(tree).as_posix()
            if not path.is_file() or path.suffix not in CR_SUFFIXES or "/Resources/StoreAssets/" in path.as_posix() \
                    or name.startswith(BUILT_FOLDERS):
                continue
            data = path.read_bytes()
            # A binary plist has no lines: a 0x0D among its bytes is data.
            if b"\r" in data and not data.startswith(b"bplist"):
                bad.append(name)
    return [f"carriage returns in {name}" for name in bad[:5]]


def store_asset_problems(tree: Path, dt: dict) -> list[str]:
    problems = []
    for kind, folder in (("icon", "Icon"), ("intents", "Intents")):
        recorded = load_json(tree / "ios/Resources/StoreAssets" / folder / "inputs.json")
        if not recorded:
            problems.append(f"no store assets for {kind}: run the iOS store assets workflow")
            continue
        try:
            names = store_inputs.files(tree, kind)
        except SystemExit:
            problems.append(f"store asset inputs for {kind} are incomplete")
            continue
        if recorded.get("files") != names or recorded.get("sha256") != store_inputs.digest(tree, names):
            problems.append(f"the {kind} store assets are stale: run the iOS store assets workflow again")
        if recorded.get("xcode_build") != dt["DTXcodeBuild"]:
            problems.append(f"the {kind} store assets were made with Xcode {recorded.get('xcode_build')}, "
                            f"this build stamps {dt['DTXcodeBuild']}")
    return problems


def pem_der(path: str) -> bytes:
    return subprocess.run(["openssl", "x509", "-in", path, "-outform", "DER"], check=True, capture_output=True).stdout


def validate_problems(tree: Path, ipa: Path, sideload: bool, dt: dict, dist_der: bytes = None,
                      symbols=defined_symbols, demangler=demangle, scan=scanned_categories) -> list[str]:
    ios = tree / "ios"
    problems = cr_problems(tree) + store_asset_problems(tree, dt) + package_problems(ios)
    version = tree_version(tree)
    docs, symbol_sets, declared = {}, {}, {}
    with zipfile.ZipFile(ipa) as z, tempfile.TemporaryDirectory(prefix="octo-tf-v-") as temp:
        names = set(z.namelist())
        bundles = ipa_bundles(z)
        app_root = bundles[0][1]
        builds = set()
        for plist in sorted(n for n in names if n.startswith(app_root) and n.endswith("/Info.plist")):
            info = plistlib.loads(z.read(plist))
            for key, value in dt.items():
                if info.get(key) != value:
                    problems.append(f"{plist}: {key} is {info.get(key)!r}, the SDK says {value!r}")
        for b, root in bundles:
            info = plistlib.loads(z.read(root + "Info.plist"))
            if info.get("CFBundleIdentifier") != b["id"]:
                problems.append(f"{root}: bundle id {info.get('CFBundleIdentifier')}, want {b['id']}")
            if info.get("CFBundleShortVersionString") != version:
                problems.append(f"{b['id']}: version {info.get('CFBundleShortVersionString')}, the tree says {version}")
            builds.add(info.get("CFBundleVersion"))
            if info.get("UIDeviceFamily") != [1]:
                problems.append(f"{b['id']}: UIDeviceFamily {info.get('UIDeviceFamily')}, want [1] (iPhone)")
            if b["path"] and "arm64" not in (info.get("UIRequiredDeviceCapabilities") or []):
                problems.append(f"{b['id']}: Info.plist lacks UIRequiredDeviceCapabilities arm64 (ITMS-90502)")
            exe = Path(temp) / b["exe"]
            exe.write_bytes(z.read(root + info["CFBundleExecutable"]))
            data = exe.read_bytes()
            if macho_archs(data) != [0x0100000C]:
                problems.append(f"{b['id']}: the executable is not arm64 only")
            sealed = sealed_entitlements(data)
            want = expected_entitlements(tree, b, store=not sideload)
            if sealed != want:
                problems.append(f"{b['id']}: sealed entitlements {sealed} are not exactly {want}")
            prof_name = root + "embedded.mobileprovision"
            if sideload:
                if prof_name in names:
                    problems.append(f"{b['id']}: the sideload IPA carries a profile")
            elif prof_name not in names:
                problems.append(f"{b['id']}: no embedded.mobileprovision")
            else:
                raw = z.read(prof_name)
                problems += profile_problems(b, raw)
                prof = profile_plist(raw)
                problems += fit_problems(want, prof.get("Entitlements", {}), b["id"])
                if dist_der is not None and dist_der not in (prof.get("DeveloperCertificates") or []):
                    problems.append(f"{b['id']}: the profile is not for the distribution certificate in dist.pem")
            if root + "_CodeSignature/CodeResources" not in names:
                problems.append(f"{b['id']}: not signed (no CodeResources)")
            manifest_name = root + "PrivacyInfo.xcprivacy"
            if manifest_name not in names:
                problems.append(f"{b['id']}: no PrivacyInfo.xcprivacy")
            else:
                declared_cats = {t.get("NSPrivacyAccessedAPIType") for t in
                                 plistlib.loads(z.read(manifest_name)).get("NSPrivacyAccessedAPITypes", [])}
                lacking = scan(exe) - declared_cats
                if lacking:
                    problems.append(f"{b['id']}: the privacy manifest lacks {sorted(lacking)}")
            if b["intents"]:
                meta = root + store_inputs.METADATA + "/extract.actionsdata"
                version_file = root + store_inputs.METADATA + "/version.json"
                if meta not in names:
                    problems.append(f"{b['id']}: no App Intents metadata")
                else:
                    docs[b["id"]] = json.loads(z.read(meta))
                    declared[b["id"]] = declared_intents(ios, b["intents"])
                    tools = json.loads(z.read(version_file)).get("toolsVersion") if version_file in names else None
                    if tools != dt["DTXcodeBuild"]:
                        problems.append(f"{b['id']}: the App Intents metadata was written by Xcode {tools}, "
                                        f"this build stamps {dt['DTXcodeBuild']}")
            symbol_sets[b["id"]] = symbols(exe)
        extra = sorted({n.split(store_inputs.METADATA)[0] for n in names if store_inputs.METADATA in n}
                       - {root for b, root in bundles if b["intents"]})
        if extra:
            problems.append(f"App Intents metadata where none belongs: {extra}")
        if len(builds) != 1:
            problems.append(f"the bundles carry different build numbers: {sorted(map(str, builds))}")
        info = plistlib.loads(z.read(app_root + "Info.plist"))
        if info.get("ITSAppUsesNonExemptEncryption") is not False:
            problems.append("Info.plist must say ITSAppUsesNonExemptEncryption false")
        if not (info.get("CFBundleIconName") and app_root + "Assets.car" in names):
            problems.append("no compiled icon (Assets.car and CFBundleIconName)")
        elif z.read(app_root + "Assets.car") != (ios / "Resources/StoreAssets/Icon/Assets.car").read_bytes():
            problems.append("Assets.car is not the committed store icon")
        if sideload and info.get(SOURCE_KEY) != "sideload":
            problems.append(f"the sideload IPA lacks {SOURCE_KEY} = sideload")
        if not sideload and SOURCE_KEY in info:
            problems.append(f"the App Store IPA must not carry {SOURCE_KEY}")
    problems += metadata_problems(docs, declared, symbol_sets, demangler)
    if docs and not problems:
        problems += canary_problems(docs, declared, symbol_sets, demangler)
    return problems


def cmd_validate(tree: Path, ipa: Path, sideload: bool) -> None:
    dist_der = None if sideload else pem_der(team_credentials(load_config())["dist_cert"])
    problems = validate_problems(tree, ipa, sideload, sdk_dt_keys(), dist_der)
    for p in problems:
        say("PROBLEM:", p)
    if problems:
        refuse(f"{ipa.name}: {len(problems)} problems")
    say("ok:", ipa.name)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def ipa_info(ipa: Path) -> dict:
    with zipfile.ZipFile(ipa) as z:
        return plistlib.loads(z.read(ipa_bundles(z)[0][1] + "Info.plist"))


def cmd_record_built(signed: Path, sideload: Path, nonce: str) -> None:
    """Binds the two built IPAs to the reservation they were built for, and to no other."""
    with run_lock():
        res = reservation()
        if res.get("nonce") != nonce or res.get("state") != "open":
            refuse("the reservation changed while this build ran; reserve again and rebuild")
        info = ipa_info(signed)
        if info.get("CFBundleVersion") != str(res["build"]) or ipa_info(sideload).get("CFBundleVersion") != str(res["build"]):
            refuse(f"the IPAs are not stamped with build {res['build']}")
        res.update({"signed": str(signed), "signed_sha256": sha256_file(signed), "version": info["CFBundleShortVersionString"],
                    "sideload": str(sideload), "sideload_sha256": sha256_file(sideload)})
        save_json(HOME / "reserved.json", res)
    say("recorded build", res["build"], "for upload")


def cmd_set_review(requires: str, host: str) -> None:
    """What the beta review build must contain (the commit set as requires_commit) and the demo
    server the review notes name."""
    if not re.fullmatch(r"[0-9a-f]{40}", requires):
        refuse("--requires takes a full 40-character commit id")
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+", host):
        refuse("--demo-host takes a host name like demo.example.org")
    if not git_is_ancestor(requires, "origin/main"):
        refuse(f"{requires} is not on octo-player's main as this checkout knows it; fetch first")
    with run_lock():
        cfg = load_config()
        cfg.update({"requires_commit": requires, "demo_host": host})
        save_json(HOME / "config.json", cfg)
    say("review builds must contain", requires, "and the notes must name", f"https://{host}")


def cmd_mark_checked(build: int) -> None:
    """Build N passed the device checks: only a checked build is ever published as an IPA."""
    with run_lock():
        if not any(u["build"] == build for u in load_json(HOME / "uploads.json", [])):
            refuse(f"build {build} was not uploaded by this tool")
        checked = load_json(HOME / "checked.json", [])
        save_json(HOME / "checked.json", sorted(set(checked + [build])))
    say("build", build, "is checked")


# ---------------------------------------------------------------- upload

def close_reservation(res: dict, burned: bool) -> None:
    if burned:
        numbers = load_json(HOME / "burned.json", [])
        save_json(HOME / "burned.json", sorted(set(numbers + [res["build"]])))
        shutil.rmtree(build_folder(res), ignore_errors=True)
    (HOME / "reserved.json").unlink(missing_ok=True)


def build_folder(res: dict) -> Path:
    return WORK / "builds" / f"{res.get('version', 'unknown')}-{res['build']}"


def keep_build(res: dict) -> Path:
    """Copies both IPAs and the symbols of build N into their own folder, which upload sends from,
    so a later build in dist/ can never change what goes to Apple."""
    folder = build_folder(res)
    folder.mkdir(parents=True, exist_ok=True)
    for key in ("signed", "sideload"):
        source = Path(res[key])
        target = folder / source.name
        if not target.exists():
            shutil.copy2(source, target)
        if sha256_file(target) != res[key + "_sha256"]:
            refuse(f"{target.name} is not the validated build")
    symbols = WORK / "dist" / f"symbols-{res['build']}"
    if symbols.exists() and not (folder / "symbols").exists():
        shutil.copytree(symbols, folder / "symbols")
    return folder


def record_upload(res: dict, upload_id) -> None:
    """Apple has build N: write it down and close the reservation."""
    keep_build(res)
    (HOME / "last-build").write_text(f"{res['build']}\n")
    uploads = load_json(HOME / "uploads.json", [])
    if not any(u["build"] == res["build"] for u in uploads):
        uploads.append({"build": res["build"], "version": res.get("version"), "commit": res["commit"],
                        "upload_id": upload_id, "signed_sha256": res["signed_sha256"],
                        "sideload_sha256": res["sideload_sha256"], "folder": str(build_folder(res))})
        save_json(HOME / "uploads.json", uploads)
    close_reservation(res, burned=False)


def upload_state(api, upload_id: str) -> str:
    data = api.call("GET", f"/v1/buildUploads/{upload_id}", ok=(200, 404)).get("data") or {}
    return ((data.get("attributes") or {}).get("state") or {}).get("state") or ""


def cmd_upload(api, ipa: Path, wait: bool, send_parts=None) -> None:
    cfg = load_config()
    with run_lock():
        res = reservation()
        if res.get("state") != "open":
            refuse(f"the reservation of build {res['build']} is {res.get('state')}; run reserve to settle it")
        if res.get("signed_sha256") != sha256_file(ipa):
            refuse(f"{ipa.name} is not the validated build of the open reservation")
        for b in BUNDLES:
            if not active_profile(api, res["profiles"][b["id"]]["id"]):
                refuse(f"{b['id']}: its profile is no longer ACTIVE; refresh-profiles, reserve and build again")
        info = ipa_info(ipa)
        version, build = info["CFBundleShortVersionString"], info["CFBundleVersion"]
        if build != str(res["build"]):
            refuse(f"{ipa.name} is build {build}, the reservation is {res['build']}")
        # The copy in builds/ is what goes to Apple; the state says an upload may be under way.
        sending = keep_build(res) / Path(res["signed"]).name
        res["state"] = "uploading"
        save_json(HOME / "reserved.json", res)
        upload_id = None
        try:
            up = api.call("POST", "/v1/buildUploads", body={"data": {"type": "buildUploads",
                "attributes": {"cfBundleShortVersionString": version, "cfBundleVersion": build, "platform": "IOS"},
                "relationships": {"app": {"data": {"type": "apps", "id": cfg["app_id"]}}}}})["data"]
            upload_id = up["id"]
            res["upload_id"] = upload_id
            save_json(HOME / "reserved.json", res)
            say("build upload", upload_id, "for", version, f"({build})")
            f = api.call("POST", "/v1/buildUploadFiles", body={"data": {"type": "buildUploadFiles",
                "attributes": {"assetType": "ASSET", "fileName": sending.name, "fileSize": sending.stat().st_size,
                               "uti": "com.apple.ipa"},
                "relationships": {"buildUpload": {"data": {"type": "buildUploads", "id": upload_id}}}}})["data"]
            (send_parts or put_parts)(sending, f["attributes"].get("uploadOperations") or [])
            api.call("PATCH", f"/v1/buildUploadFiles/{f['id']}", body={"data": {
                "type": "buildUploadFiles", "id": f["id"], "attributes": {"uploaded": True}}})
        except BaseException:
            # Read Apple's side back before deciding: a build Apple has is never burned.
            state = upload_state(api, upload_id) if upload_id else ""
            if state not in APPLE_HAS_UPLOAD:
                if upload_id:
                    api.call("DELETE", f"/v1/buildUploads/{upload_id}", ok=(204, 404, 409))
                close_reservation(res, burned=True)
                say("upload failed; build", res["build"], "is burned; reserve again and rerun with --no-build")
                raise
            say("Apple has the upload (state", state + "); recording it")
        record_upload(res, upload_id)
        for key in ("signed", "sideload"):
            Path(res[key]).unlink(missing_ok=True)
    say("committed; Apple is processing build", build)
    if wait:
        wait_processing(api, cfg, version, build)


def put_parts(ipa: Path, ops: list) -> None:
    if not ops:
        refuse("the reservation has no upload operations")
    with open(ipa, "rb") as fh:
        for i, op in enumerate(ops, 1):
            fh.seek(op["offset"])
            chunk = fh.read(op["length"])
            for attempt in range(4):
                req = urllib.request.Request(op["url"], data=chunk, method=op.get("method") or "PUT")
                for h in op.get("requestHeaders") or []:
                    req.add_header(h["name"], h["value"])
                try:
                    with urllib.request.urlopen(req, timeout=seconds("PART", 300)) as r:
                        if r.status in (200, 201, 204):
                            break
                except (urllib.error.URLError, TimeoutError) as e:
                    if attempt == 3:
                        raise Refused(f"part {i} did not go: {type(e).__name__}") from None
                    time.sleep(seconds("RETRY", 1) * 2 ** attempt)
            say(f"part {i}/{len(ops)} sent")


def wait_processing(api, cfg: dict, version: str, build: str, sleep=time.sleep) -> None:
    deadline = time.time() + seconds("PROCESSING", 60 * 60)
    while time.time() < deadline:
        res = api.call("GET", "/v1/builds", params={"filter[app]": cfg["app_id"], "filter[version]": build,
                                                    "filter[preReleaseVersion.version]": version, "limit": 1})
        if res.get("data"):
            state = res["data"][0]["attributes"].get("processingState")
            say("build", build, "processing", state)
            if state == "VALID":
                return
            if state in ("FAILED", "INVALID"):
                refuse(f"Apple did not accept build {build}: {state}")
        sleep(seconds("POLL", 30))
    refuse("timed out waiting for processing")


# ---------------------------------------------------------------- publish

def find_build(api, cfg: dict, build: int) -> dict:
    res = api.call("GET", "/v1/builds", params={"filter[app]": cfg["app_id"], "filter[version]": str(build), "limit": 5})
    for b in res.get("data", []):
        if b["attributes"].get("version") == str(build):
            a = b["attributes"]
            if a.get("expired"):
                refuse(f"build {build} has expired")
            if a.get("processingState") != "VALID":
                refuse(f"build {build} is {a.get('processingState')}, not VALID")
            return b
    refuse(f"App Store Connect has no build {build}")


def whats_new_problems(text: str) -> list[str]:
    problems = []
    if not text.strip():
        problems.append("What to Test is empty")
    if len(text) > WHATS_NEW_LIMIT:
        problems.append(f"What to Test is {len(text)} characters; the most is {WHATS_NEW_LIMIT}")
    if "—" in text or "–" in text:
        problems.append("What to Test uses a long dash")
    if re.search(r"\bpreview", text, re.I):
        problems.append("What to Test calls a song a preview")
    return problems


def cmd_whats_new(api, build: int, text: str) -> None:
    problems = whats_new_problems(text)
    if problems:
        refuse("; ".join(problems))
    cfg = load_config()
    b = find_build(api, cfg, build)
    locs = api.call("GET", f"/v1/builds/{b['id']}/betaBuildLocalizations", params={"limit": 50}).get("data", [])
    mine = next((l for l in locs if l["attributes"].get("locale") == "en-US"), None)
    if mine and mine["attributes"].get("whatsNew") == text:
        say("What to Test for build", build, "is already set")
        return
    if mine:
        api.call("PATCH", f"/v1/betaBuildLocalizations/{mine['id']}", body={"data": {
            "type": "betaBuildLocalizations", "id": mine["id"], "attributes": {"whatsNew": text}}})
    else:
        api.call("POST", "/v1/betaBuildLocalizations", body={"data": {"type": "betaBuildLocalizations",
            "attributes": {"whatsNew": text, "locale": "en-US"},
            "relationships": {"build": {"data": {"type": "builds", "id": b["id"]}}}}})
    say("What to Test set for build", build)


def subsonic_ping(host: str, user: str, password: str, opener=None) -> bool:
    """A Subsonic ping with token sign-in, sent as a form body so nothing lands in a URL."""
    salt = os.urandom(8).hex()
    token = hashlib.md5((password + salt).encode()).hexdigest()
    keep_secret(token)
    keep_secret(salt)
    body = urllib.parse.urlencode({"u": user, "t": token, "s": salt, "v": "1.16.1", "c": "Octo", "f": "json"}).encode()
    req = urllib.request.Request(f"https://{host}/rest/ping.view", data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with (opener or urllib.request.urlopen)(req, timeout=seconds("PING", 30)) as r:
            answer = json.loads(r.read())
    except Exception as e:  # any failure is a refusal, never a printed URL
        refuse(f"the demo server did not answer: {redact(type(e).__name__)}")
    return (answer.get("subsonic-response") or {}).get("status") == "ok"


def stream_only(host: str, user: str, password: str, opener=None) -> bool:
    """Whether the demo server says it is set to Stream only. The server offers no such signal
    yet, so this refuses and a review never goes out unchecked."""
    refuse("Stream only check not built: it waits for the outside music setting's contract")


def external_problems(api, cfg: dict, build: dict, commit: str, ping=subsonic_ping, mode=stream_only,
                      is_ancestor=None) -> list[str]:
    problems = []
    app_id = cfg["app_id"]
    locs = api.call("GET", f"/v1/apps/{app_id}/betaAppLocalizations").get("data", [])
    en = next((l["attributes"] for l in locs if l["attributes"].get("locale") == "en-US"), {})
    if not en.get("description") or not en.get("feedbackEmail"):
        problems.append("Test Information lacks the beta description or the feedback email")
    detail = api.call("GET", f"/v1/apps/{app_id}/betaAppReviewDetail").get("data", {}).get("attributes", {})
    for key in ("contactFirstName", "contactLastName", "contactPhone", "contactEmail"):
        if not detail.get(key):
            problems.append(f"review details lack {key}")
    user, password = detail.get("demoAccountName") or "", detail.get("demoAccountPassword") or ""
    keep_secret(user)
    keep_secret(password)
    if not detail.get("demoAccountRequired") or not user or not password:
        problems.append("review details must say sign-in is required and give the demo account")
    host = cfg.get("demo_host") or ""
    if not host:
        problems.append("config has no demo_host")
    elif f"https://{host}" not in (detail.get("notes") or ""):
        problems.append(f"the review notes do not give https://{host}")
    elif user and password:
        if not ping(host, user, password):
            problems.append("the demo account cannot sign in to the demo server")
        elif not mode(host, user, password):
            problems.append("the demo server is not set to Stream only")
    required = cfg.get("requires_commit")
    if not required:
        problems.append("config has no requires_commit (set-review sets the commit the review build must contain)")
    elif not (is_ancestor or git_is_ancestor)(required, commit):
        problems.append(f"build {build['attributes'].get('version')} does not contain {required}")
    group = api.call("GET", f"/v1/betaGroups/{cfg['groups']['Public']}").get("data", {}).get("attributes", {})
    if group.get("iosBuildsAvailableForAppleSiliconMac") is not False or group.get("iosBuildsAvailableForAppleVision") is not False:
        problems.append("Public is open to Macs or Vision Pro; run setup-testflight --apply")
    return problems


def git_is_ancestor(older: str, newer: str) -> bool:
    return subprocess.run(["git", "-c", "safe.directory=*", "-C", str(REPO), "merge-base", "--is-ancestor", older, newer],
                          capture_output=True).returncode == 0


def build_commit(build: int) -> str:
    for u in load_json(HOME / "uploads.json", []):
        if u["build"] == build:
            return u["commit"]
    refuse(f"build {build} was not uploaded by this tool")


def external_state(api, build_id: str) -> str:
    data = api.call("GET", f"/v1/builds/{build_id}/buildBetaDetail").get("data", {})
    return (data.get("attributes") or {}).get("externalBuildState") or ""


def cmd_external(api, build: int, **checks) -> None:
    cfg = load_config()
    b = find_build(api, cfg, build)
    problems = external_problems(api, cfg, b, build_commit(build), **checks)
    for p in problems:
        say("PROBLEM:", p)
    if problems:
        refuse(f"build {build} cannot go to beta review yet")
    public = cfg["groups"]["Public"]
    in_group = {x["id"] for x in api.call("GET", f"/v1/betaGroups/{public}/builds", params={"limit": 200}).get("data", [])}
    if b["id"] not in in_group:
        api.call("POST", f"/v1/betaGroups/{public}/relationships/builds", body={"data": [{"type": "builds", "id": b["id"]}]})
        say("added build", build, "to Public")
    state = external_state(api, b["id"])
    if state in SUBMITTED_STATES:
        say("build", build, "is already", state)
        return
    api.call("POST", "/v1/betaAppReviewSubmissions", body={"data": {"type": "betaAppReviewSubmissions",
        "relationships": {"build": {"data": {"type": "builds", "id": b["id"]}}}}})
    say("submitted build", build, "for beta review")


def cmd_check(api, build: int) -> str:
    cfg = load_config()
    state = external_state(api, find_build(api, cfg, build)["id"])
    say("build", build, "beta review:", state or "not submitted")
    return state


def cmd_open_link(api) -> None:
    cfg = load_config()
    public = cfg["groups"]["Public"]
    builds = api.call("GET", f"/v1/betaGroups/{public}/builds", params={"limit": 200}).get("data", [])
    if not any(external_state(api, b["id"]) in APPROVED_STATES for b in builds):
        refuse("no build in Public is approved yet")
    group = api.call("GET", f"/v1/betaGroups/{public}").get("data", {}).get("attributes", {})
    if not group.get("publicLinkEnabled"):
        group = api.call("PATCH", f"/v1/betaGroups/{public}", body={"data": {"type": "betaGroups", "id": public,
            "attributes": {"publicLinkEnabled": True, "publicLinkLimitEnabled": False}}})["data"]["attributes"]
    say("public link:", group.get("publicLink"))


def cmd_resubmit(api, build: int) -> None:
    cfg = load_config()
    b = find_build(api, cfg, build)
    state = external_state(api, b["id"])
    if state != "BETA_REJECTED":
        refuse(f"build {build} is {state or 'not submitted'}, not rejected")
    api.call("POST", "/v1/betaAppReviewSubmissions", body={"data": {"type": "betaAppReviewSubmissions",
        "relationships": {"build": {"data": {"type": "builds", "id": b["id"]}}}}})
    say("submitted build", build, "again")


# ---------------------------------------------------------------- status

def days_left(stamp: str, now: datetime.datetime) -> int:
    when = datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    return (when - now).days


def newest_days(builds: list, now: datetime.datetime):
    """Days left on the newest build testers can still install, or None when none is live."""
    live = [days_left(b["attributes"]["expirationDate"], now) for b in builds
            if not b["attributes"].get("expired") and b["attributes"].get("expirationDate")
            and b["attributes"].get("processingState", "VALID") == "VALID"]
    return max(live) if live else None


def status_lines(api, cfg: dict, now: datetime.datetime) -> tuple[list[str], int]:
    """The status, and how long testers can still install a build: per group the newest live
    build counts, and the group with the fewest days decides. Team gets every build; Public
    counts once it has had a build."""
    lines, values = [], []
    team = newest_days(every(api, "/v1/builds", {"filter[app]": cfg["app_id"], "filter[expired]": "false"}), now)
    if team is not None:
        lines.append(f"Team: the newest build has {team} days left")
        values.append(team)
    elif load_json(HOME / "uploads.json", []):
        lines.append("Team: no build is live")
        values.append(0)
    public_id = (cfg.get("groups") or {}).get("Public")
    if public_id:
        builds = every(api, f"/v1/betaGroups/{public_id}/builds", {})
        public = newest_days(builds, now)
        if public is not None:
            lines.append(f"Public: the newest build has {public} days left")
            values.append(public)
        elif builds:
            lines.append("Public: no build is live")
            values.append(0)
    fewest = min(values) if values else None
    for b in BUNDLES:
        entry = (cfg.get("profiles") or {}).get(b["id"])
        if entry and entry.get("expires"):
            lines.append(f"profile {b['id']}: {days_left(entry['expires'], now)} days left")
    return lines, fewest


def certificate_days(pem: Path, now: datetime.datetime):
    """Days left on the team's distribution certificate (StreamNook signs with it too), or None
    when its file is not there."""
    if not pem.is_file():
        return None
    from cryptography import x509
    return (x509.load_pem_x509_certificate(pem.read_bytes()).not_valid_after_utc - now).days


def cmd_status(api, brief: bool) -> int:
    cfg = load_config()
    now = datetime.datetime.now(datetime.timezone.utc)
    lines, fewest = status_lines(api, cfg, now)
    cert = certificate_days(Path(team_credentials(cfg)["dist_cert"]), now)
    if cert is not None:
        lines.append(f"distribution certificate (StreamNook's too): {cert} days left")
    if brief:
        warn = False
        if cert is not None and cert <= EXPIRY_WARN_DAYS:
            say(f"the distribution certificate has {cert} days left: renew it for both apps")
            warn = True
        if fewest is None:
            say("no TestFlight build is live")
        else:
            say(f"fewest days left on a TestFlight build: {fewest}")
            warn = warn or fewest <= EXPIRY_WARN_DAYS
        return 2 if warn else 0
    for line in lines:
        say(line)
    return 0


# ---------------------------------------------------------------- main

def redacting_hook(kind, value, tb) -> None:
    """Even an error nobody caught is printed through redact(), never as a raw traceback."""
    print("octo_asc: " + redact(f"{kind.__name__}: {value}"), file=sys.stderr)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("setup-ids", "setup-profiles", "refresh-profiles", "setup-testflight"):
        sub.add_parser(name).add_argument("--apply", action="store_true")
    sub.add_parser("reserve").add_argument("--ref", required=True)
    for name in ("prepare", "sideload"):
        q = sub.add_parser(name)
        q.add_argument("--tree", type=Path, required=True)
        q.add_argument("--unsigned", type=Path, required=True)
        q.add_argument("--out", type=Path, required=True)
        if name == "sideload":
            q.add_argument("--build", type=int)
    q = sub.add_parser("validate")
    q.add_argument("--tree", type=Path, required=True)
    q.add_argument("--ipa", type=Path, required=True)
    q.add_argument("--sideload", action="store_true")
    q = sub.add_parser("record-built")
    q.add_argument("--signed", type=Path, required=True)
    q.add_argument("--sideload", type=Path, required=True)
    q.add_argument("--nonce", required=True)
    q = sub.add_parser("set-review")
    q.add_argument("--requires", required=True)
    q.add_argument("--demo-host", required=True)
    sub.add_parser("mark-checked").add_argument("build", type=int)
    q = sub.add_parser("upload")
    q.add_argument("ipa", type=Path)
    q.add_argument("--wait", action="store_true")
    q = sub.add_parser("publish")
    q.add_argument("action", choices=["whats-new", "external", "check", "open-link", "resubmit"])
    q.add_argument("--build", type=int)
    q.add_argument("--file", type=Path)
    q = sub.add_parser("status")
    q.add_argument("--brief", action="store_true")
    sub.add_parser("entitlements").add_argument("ipa", type=Path)
    args = p.parse_args(argv)
    sys.excepthook = redacting_hook

    try:
        if args.cmd in ("prepare", "sideload", "validate", "record-built", "entitlements", "set-review", "mark-checked"):
            if args.cmd == "prepare":
                cmd_prepare(args.tree, args.unsigned, args.out)
            elif args.cmd == "sideload":
                cmd_sideload(args.tree, args.unsigned, args.out, args.build)
            elif args.cmd == "validate":
                cmd_validate(args.tree, args.ipa, args.sideload)
            elif args.cmd == "record-built":
                cmd_record_built(args.signed, args.sideload, args.nonce)
            elif args.cmd == "set-review":
                cmd_set_review(args.requires, args.demo_host)
            elif args.cmd == "mark-checked":
                cmd_mark_checked(args.build)
            else:
                cmd_entitlements(args.ipa)
            return 0
        cfg = load_json(HOME / "config.json", {"streamnook_config": str(STREAMNOOK_CONFIG)})
        api = Api(team_credentials(cfg))
        if args.cmd == "setup-ids":
            cmd_setup_ids(api, args.apply)
        elif args.cmd in ("setup-profiles", "refresh-profiles"):
            setup_profiles(api, args.apply, refresh=args.cmd == "refresh-profiles")
        elif args.cmd == "setup-testflight":
            cmd_setup_testflight(api, args.apply)
        elif args.cmd == "reserve":
            cmd_reserve(api, args.ref)
        elif args.cmd == "upload":
            cmd_upload(api, args.ipa, args.wait)
        elif args.cmd == "status":
            return cmd_status(api, args.brief)
        else:
            if args.action in ("whats-new", "external", "check", "resubmit") and args.build is None:
                refuse(f"publish {args.action} needs --build")
            if args.action == "whats-new":
                if not args.file:
                    refuse("publish whats-new needs --file")
                # A Windows checkout writes CRLF; testers read the text with plain line breaks.
                cmd_whats_new(api, args.build, args.file.read_text(encoding="utf-8").replace("\r\n", "\n").strip())
            elif args.action == "external":
                cmd_external(api, args.build)
            elif args.action == "check":
                cmd_check(api, args.build)
            elif args.action == "open-link":
                cmd_open_link(api)
            else:
                cmd_resubmit(api, args.build)
        return 0
    except Refused as e:
        print("octo_asc: " + redact(str(e)), file=sys.stderr)
        return 1
    except Exception as e:  # never a traceback that could carry a secret
        print("octo_asc: " + redact(f"{type(e).__name__}: {e}"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
