"""Tests for octo_asc.py, against a fake App Store Connect and fixture bundles.

Run: python3 -I -m unittest discover -s ios/scripts/release -p 'test_*.py' -v
"""
import contextlib
import copy
import datetime
import hashlib
import importlib.util
import io
import json
import os
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("octo_asc", HERE / "octo_asc.py")
asc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asc)
store_inputs = asc.store_inputs

TEAM = asc.TEAM
DT = {"DTCompiler": "com.apple.compilers.llvm.clang.1_0", "DTPlatformBuild": "23A1", "DTPlatformName": "iphoneos",
      "DTPlatformVersion": "27.1", "DTSDKBuild": "23A1", "DTSDKName": "iphoneos27.1", "DTXcode": "2710",
      "DTXcodeBuild": "27A9275"}
CERT = b"the distribution certificate"


class FakeApi:
    """Answers by (method, path pattern); records every call."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def call(self, method, path, body=None, params=None, ok=(200, 201, 204)):
        self.calls.append((method, path, body, params))
        for m, pattern, answer in self.routes:
            if m == method and re.fullmatch(pattern, path):
                return answer(body, params) if callable(answer) else copy.deepcopy(answer)
        raise AssertionError(f"unexpected {method} {path}")

    def methods(self):
        return [c[0] for c in self.calls]


def plist(data) -> bytes:
    return plistlib.dumps(data, fmt=plistlib.FMT_XML)


def macho(entitlements, arch=0x0100000C) -> bytes:
    """A thin 64-bit Mach-O with one LC_CODE_SIGNATURE whose superblob seals these entitlements."""
    ent = plist(entitlements) if entitlements is not None else b""
    blob = struct.pack(">II", 0xFADE7171, 8 + len(ent)) + ent
    superblob = struct.pack(">III", 0xFADE0CC0, 20 + len(blob), 1) + struct.pack(">II", 5, 20) + blob
    dataoff = 64
    header = struct.pack("<IiiIIIII", 0xFEEDFACF, arch, 0, 2, 1, 16, 0, 0)
    cmd = struct.pack("<IIII", 0x1D, 16, dataoff, len(superblob))
    return (header + cmd).ljust(dataoff, b"\0") + superblob


def expected(bundle, store=True):
    own = {"com.apple.security.application-groups": [asc.GROUP]}
    if bundle["id"] == asc.APP_ID:
        own["com.apple.developer.siri"] = True
    if not store:
        return own
    return {**own, "application-identifier": f"{TEAM}.{bundle['id']}", "com.apple.developer.team-identifier": TEAM,
            "get-task-allow": False, "beta-reports-active": True}


def profile(bundle, group=True, cert=CERT, devices=False) -> bytes:
    ent = {"application-identifier": f"{TEAM}.{bundle['id']}", "com.apple.developer.team-identifier": TEAM,
           "keychain-access-groups": [f"{TEAM}.*", "com.apple.token"], "get-task-allow": False,
           "beta-reports-active": True}
    if group:
        ent["com.apple.security.application-groups"] = [asc.GROUP]
    if bundle["id"] == asc.APP_ID:
        ent["com.apple.developer.siri"] = True
    data = {"Entitlements": ent, "TeamIdentifier": [TEAM], "DeveloperCertificates": [cert]}
    if devices:
        data["ProvisionedDevices"] = ["0000"]
    return b"CMS junk" + plist(data) + b"junk"


INTENTS_SOURCE = """import AppIntents
import OctoSystem

public struct MusicItemEntity: AppEntity {
    public var id: String
}

public struct MusicItemQuery: EntityStringQuery {
    public init() {}
}

public struct PlayItemIntent: AudioPlaybackIntent {
    public init() {}
}

struct Helper: Error, CustomLocalizedStringResourceConvertible {}

public struct OctoIntentsPackage: AppIntentsPackage {}
"""

SHORTCUTS_SOURCE = """import AppIntents

struct OctoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] { [] }
}

struct OctoAppIntents: AppIntentsPackage {}
"""


PACKAGE_SOURCE = """// swift-tools-version: 6.0
let package = Package(name: "Octo", targets: [
    .target(name: "OctoKit", dependencies: [.target(name: "CSQLite", condition: .when(platforms: [.linux]))]),
    .target(name: "Octo", dependencies: ["OctoKit", "ShareInbox", "OctoSystem", "OctoIntents"], resources: [.copy("CoverArt")]),
    .target(name: "OctoIntents", dependencies: ["OctoSystem"]),
    .target(name: "OctoShare", dependencies: ["ShareInbox"],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-u", "-Xlinker", "_octo_share_extension"])]),
    .target(name: "OctoWidgets", dependencies: ["OctoSystem", "OctoIntents"],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-e", "-Xlinker", "_main"])]),
])
"""


def metadata_doc(bundles, with_provider):
    """A metadata document in Xcode's shape, naming the OctoIntents types for these bundles."""
    def entry(name, mangled):
        return {"fullyQualifiedTypeName": f"OctoIntents.{name}", "mangledTypeName": mangled, "mangledTypeNameV2": mangled,
                "mangledTypeNameByBundleIdentifier": {b: mangled for b in bundles},
                "mangledTypeNameByBundleIdentifierV2": {b: mangled for b in bundles},
                "parameters": [{"valueType": {"mangledTypeName": "SS"}}]}
    doc = {"version": 1, "generator": {"name": "xcode-tools", "version": "27A9275"},
           "actions": {"PlayItemIntent": entry("PlayItemIntent", "11OctoIntents14PlayItemIntentV")},
           "entities": {"MusicItemEntity": entry("MusicItemEntity", "11OctoIntents15MusicItemEntityV")},
           "queries": {"MusicItemQuery": entry("MusicItemQuery", "11OctoIntents14MusicItemQueryV")},
           "enums": []}
    if with_provider:
        doc["autoShortcutProviderMangledName"] = "4Octo0A9ShortcutsV"
    return doc


def fake_demangle(mangled):
    return {"4Octo0A9ShortcutsV": "Octo.OctoShortcuts"}.get(mangled, "Unknown." + mangled)


SYMBOLS = {asc.APP_ID: {"_$s11OctoIntents14PlayItemIntentVMn", "_$s11OctoIntents15MusicItemEntityVMn",
                        "_$s11OctoIntents14MusicItemQueryVMn", "_$s4Octo0A9ShortcutsVMn"},
           asc.APP_ID + ".share": set(),
           asc.APP_ID + ".widgets": {"_$s11OctoIntents14PlayItemIntentVMn", "_$s11OctoIntents15MusicItemEntityVMn",
                                     "_$s11OctoIntents14MusicItemQueryVMn"}}
EXE_TO_ID = {b["exe"]: b["id"] for b in asc.BUNDLES}


def fake_symbols(path):
    return SYMBOLS[EXE_TO_ID[Path(path).name]]


def make_tree(root: Path) -> Path:
    """A repository tree with the files the release tools read."""
    ios = root / "ios"
    files = {
        "Info.plist": plist({"CFBundleShortVersionString": "1.11.0", "CFBundleVersion": "1",
                             "ITSAppUsesNonExemptEncryption": False}),
        "OctoShare-Info.plist": plist({"CFBundleShortVersionString": "1.11.0", "CFBundleVersion": "1"}),
        "OctoWidgets-Info.plist": plist({"CFBundleShortVersionString": "1.11.0", "CFBundleVersion": "1"}),
        "PrivacyInfo.xcprivacy": plist({"NSPrivacyTracking": False, "NSPrivacyCollectedDataTypes": [
            {"NSPrivacyCollectedDataType": "NSPrivacyCollectedDataTypeCoarseLocation"}],
            "NSPrivacyAccessedAPITypes": [{"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                                           "NSPrivacyAccessedAPITypeReasons": ["CA92.1", "1C8F.1"]}]}),
        "AppIcon.icon/icon.json": b"{}",
        "Launch.xcassets/Contents.json": b"{}",
        "Package.swift": PACKAGE_SOURCE.encode(),
        "project.yml": b"name: Octo\n",
        "xtool.yml": b"version: 1\n",
        "Sources/OctoIntents/MusicIntents.swift": INTENTS_SOURCE.encode(),
        "Sources/Octo/System/OctoShortcuts.swift": SHORTCUTS_SOURCE.encode(),
        "Sources/OctoWidgets/WidgetIntents.swift": b"import AppIntents\nstruct OctoWidgetIntents: AppIntentsPackage {}\n",
        "Resources/StoreAssets/Icon/Assets.car": b"compiled icon",
        "Resources/StoreAssets/Icon/AppIcon60x60@2x.png": b"png",
        "Resources/StoreAssets/Icon/partial-Info.plist": plist({"CFBundleIcons": {"CFBundlePrimaryIcon": {
            "CFBundleIconName": "AppIcon", "CFBundleIconFiles": ["AppIcon60x60"]}}}),
    }
    for b in asc.BUNDLES:
        files[b["entitlements"]] = plist(expected(b, store=False))
    for exe, ids in (("Octo", [asc.APP_ID, asc.APP_ID + ".widgets"]), ("OctoWidgets", [asc.APP_ID, asc.APP_ID + ".widgets"])):
        files[f"Resources/StoreAssets/Intents/{exe}/Metadata.appintents/extract.actionsdata"] = json.dumps(
            metadata_doc(ids, with_provider=exe == "Octo")).encode()
        files[f"Resources/StoreAssets/Intents/{exe}/Metadata.appintents/version.json"] = \
            b'{"version":"3.0","toolsVersion":"27A9275"}'
    for name, data in files.items():
        path = ios / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    wf = root / store_inputs.WORKFLOW
    wf.parent.mkdir(parents=True, exist_ok=True)
    wf.write_text("name: iOS store assets\n")
    store_inputs.record(root, "icon", ios / "Resources/StoreAssets/Icon/inputs.json", DT["DTXcodeBuild"])
    store_inputs.record(root, "intents", ios / "Resources/StoreAssets/Intents/inputs.json", DT["DTXcodeBuild"])
    return root


def make_ipa(path: Path, tree: Path, sideload=False, edit=None) -> Path:
    """A signed-looking IPA the way prepare (or sideload) leaves one; `edit(files)` changes it."""
    ios = tree / "ios"
    files = {}
    app = "Payload/Octo.app/"
    for b in asc.BUNDLES:
        root = app + (b["path"] + "/" if b["path"] else "")
        info = {"CFBundleIdentifier": b["id"], "CFBundleExecutable": b["exe"], "CFBundleShortVersionString": "1.11.0",
                "CFBundleVersion": "7", "UIDeviceFamily": [1], **DT}
        if b["path"]:
            info["UIRequiredDeviceCapabilities"] = ["arm64"]
            info["CFBundleSupportedPlatforms"] = ["iPhoneOS"]
        else:
            info.update({"CFBundleIconName": "AppIcon", "ITSAppUsesNonExemptEncryption": False})
            if sideload:
                info[asc.SOURCE_KEY] = "sideload"
        files[root + "Info.plist"] = info
        files[root + b["exe"]] = macho(expected(b, store=not sideload))
        files[root + "_CodeSignature/CodeResources"] = b"seal"
        files[root + "PrivacyInfo.xcprivacy"] = (ios / "PrivacyInfo.xcprivacy").read_bytes()
        if not sideload:
            files[root + "embedded.mobileprovision"] = profile(b)
        if b["intents"]:
            meta = ios / "Resources/StoreAssets/Intents" / b["exe"] / "Metadata.appintents"
            for f in meta.iterdir():
                files[root + "Metadata.appintents/" + f.name] = f.read_bytes()
    files[app + "Assets.car"] = (ios / "Resources/StoreAssets/Icon/Assets.car").read_bytes()
    files[app + "Octo_Octo.bundle/Info.plist"] = {"CFBundleIdentifier": "app.winters.octo.resources", **DT}
    if edit:
        edit(files)
    with zipfile.ZipFile(path, "w") as z:
        for name, data in files.items():
            if data is None:
                continue
            z.writestr(name, plist(data) if isinstance(data, dict) else data)
    return path


class Home(unittest.TestCase):
    """Each test gets its own HOME, WORK and REPO."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.saved = (asc.HOME, asc.WORK, asc.REPO, asc.git, asc.release_versions, asc._SECRETS.copy())
        asc.HOME, asc.WORK, asc.REPO = root / "home", root / "work", root / "repo"
        asc.HOME.mkdir()
        asc.WORK.mkdir()
        asc.git = lambda *args: "tree1234"
        asc.release_versions = lambda ref: ("1.11.0", [1, 11])
        sn = root / "sn"
        sn.mkdir()
        (sn / "config.json").write_text(json.dumps({"key_id": "K", "issuer_id": "I", "key_path": str(sn / "k.p8"),
                                                     "certificate_id": "CERT1"}))
        self.cfg = {"streamnook_config": str(sn / "config.json"), "app_id": "APP1",
                    "bundles": {b["id"]: f"PK-{b['exe']}" for b in asc.BUNDLES},
                    "profiles": {b["id"]: {"id": f"PROF-{b['exe']}", "expires": "2027-10-18T00:00:00Z"} for b in asc.BUNDLES},
                    "groups": {"Team": "G-TEAM", "Public": "G-PUBLIC"}, "demo_host": "demo.example.test",
                    "requires_commit": "a" * 40}
        asc.save_json(asc.HOME / "config.json", self.cfg)
        for b in asc.BUNDLES:
            (asc.HOME / b["profile"]).write_bytes(profile(b))
        self.tree = make_tree(root / "src" / ("b" * 40))

    def tearDown(self):
        asc.HOME, asc.WORK, asc.REPO, asc.git, asc.release_versions, secrets = self.saved
        asc._SECRETS.clear()
        asc._SECRETS.update(secrets)
        self.temp.cleanup()

    def problems(self, sideload=False, edit=None, dt=DT, cert=CERT):
        ipa = make_ipa(Path(self.temp.name) / "x.ipa", self.tree, sideload, edit)
        return asc.validate_problems(self.tree, ipa, sideload, dt, None if sideload else cert,
                                     symbols=fake_symbols, demangler=fake_demangle, scan=lambda p: set())

    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"{text!r} not in {problems}")


class EntitlementTests(Home):
    def test_the_store_set_is_the_bundles_own_file_and_xcodes_four_keys(self):
        for b in asc.BUNDLES:
            self.assertEqual(asc.expected_entitlements(self.tree, b, store=True), expected(b, store=True))
            self.assertEqual(asc.expected_entitlements(self.tree, b, store=False), expected(b, store=False))
        self.assertNotIn("keychain-access-groups", asc.expected_entitlements(self.tree, asc.BUNDLES[0], True))

    def test_the_set_must_fit_its_profile(self):
        b = asc.BUNDLES[0]
        ent = asc.profile_plist(profile(b))["Entitlements"]
        self.assertEqual(asc.fit_problems(expected(b), ent, b["id"]), [])
        without = asc.profile_plist(profile(b, group=False))["Entitlements"]
        self.assertTrue(asc.fit_problems(expected(b), without, b["id"]))
        siri_on_share = {**expected(asc.BUNDLES[1]), "com.apple.developer.siri": True}
        self.assertTrue(asc.fit_problems(siri_on_share, asc.profile_plist(profile(asc.BUNDLES[1]))["Entitlements"], "x"))

    def test_wildcards_grant_only_what_they_cover(self):
        self.assertTrue(asc.granted([f"{TEAM}.app.winters.octo"], [f"{TEAM}.*"]))
        self.assertFalse(asc.granted(["OTHER.app"], [f"{TEAM}.*"]))
        self.assertTrue(asc.granted(False, False))
        self.assertTrue(asc.granted(True, True))
        self.assertFalse(asc.granted(True, False))

    def test_a_sealed_wildcard_keychain_group_is_refused(self):
        def edit(files):
            files["Payload/Octo.app/Octo"] = macho({**expected(asc.BUNDLES[0]), "keychain-access-groups": [f"{TEAM}.*"]})
        self.assertProblem(self.problems(edit=edit), "sealed entitlements")

    def test_an_extension_sealed_as_the_app_is_refused(self):
        def edit(files):
            files["Payload/Octo.app/PlugIns/OctoShare.appex/OctoShare"] = macho(expected(asc.BUNDLES[0]))
        self.assertProblem(self.problems(edit=edit), "app.winters.octo.share: sealed entitlements")

    def test_profile_problems(self):
        b = asc.BUNDLES[0]
        self.assertEqual(asc.profile_problems(b, profile(b)), [])
        self.assertTrue(asc.profile_problems(b, profile(b, group=False)))
        self.assertTrue(asc.profile_problems(b, profile(b, devices=True)))
        self.assertTrue(asc.profile_problems(b, profile(asc.BUNDLES[1])))


class PrivacyTests(unittest.TestCase):
    def test_scanned_reasons_are_added_and_declared_data_kept(self):
        base = {"NSPrivacyCollectedDataTypes": [{"NSPrivacyCollectedDataType": "NSPrivacyCollectedDataTypeCoarseLocation"}],
                "NSPrivacyAccessedAPITypes": [{"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                                               "NSPrivacyAccessedAPITypeReasons": ["CA92.1", "1C8F.1"]}]}
        out = asc.privacy_manifest(base, {"NSPrivacyAccessedAPICategoryUserDefaults", "NSPrivacyAccessedAPICategoryDiskSpace"})
        self.assertEqual(out["NSPrivacyCollectedDataTypes"], base["NSPrivacyCollectedDataTypes"])
        cats = {t["NSPrivacyAccessedAPIType"]: t["NSPrivacyAccessedAPITypeReasons"] for t in out["NSPrivacyAccessedAPITypes"]}
        self.assertEqual(cats["NSPrivacyAccessedAPICategoryUserDefaults"], ["CA92.1", "1C8F.1"])
        self.assertEqual(cats["NSPrivacyAccessedAPICategoryDiskSpace"], ["E174.1"])


class ValidateTests(Home):
    def test_a_good_store_build_passes_with_both_canaries(self):
        self.assertEqual(self.problems(), [])

    def test_a_good_sideload_build_passes(self):
        self.assertEqual(self.problems(sideload=True), [])

    def test_ipad_is_refused(self):
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/Info.plist"].update(UIDeviceFamily=[1, 2])),
                           "UIDeviceFamily")

    def test_an_extension_without_arm64_is_refused(self):
        def edit(files):
            files["Payload/Octo.app/PlugIns/OctoWidgets.appex/Info.plist"].pop("UIRequiredDeviceCapabilities")
        self.assertProblem(self.problems(edit=edit), "ITMS-90502")

    def test_differing_versions_and_builds_are_refused(self):
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/PlugIns/OctoShare.appex/Info.plist"]
                                         .update(CFBundleShortVersionString="1.10.0")), "version 1.10.0")
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/PlugIns/OctoShare.appex/Info.plist"]
                                         .update(CFBundleVersion="8")), "different build numbers")

    def test_dt_keys_not_from_the_sdk_are_refused_in_every_bundle(self):
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/Octo_Octo.bundle/Info.plist"]
                                         .update(DTXcode="10989")), "Octo_Octo.bundle/Info.plist: DTXcode")

    def test_a_profile_without_the_group_or_for_another_certificate_is_refused(self):
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/embedded.mobileprovision":
                                                                  profile(asc.BUNDLES[0], group=False)})), "App Group")
        self.assertProblem(self.problems(cert=b"another"), "not for the distribution certificate")
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/embedded.mobileprovision":
                                                                  profile(asc.BUNDLES[0], devices=True)})), "not an App Store profile")

    def test_stale_store_assets_or_another_xcode_are_refused(self):
        (self.tree / "ios/AppIcon.icon/icon.json").write_text('{"changed": true}')
        self.assertProblem(self.problems(), "icon store assets are stale")
        self.assertProblem(self.problems(dt={**DT, "DTXcodeBuild": "27B52"}), "made with Xcode 27A9275")

    def test_the_committed_icon_must_be_the_one_shipped(self):
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/Assets.car": b"other"})),
                           "not the committed store icon")

    def test_metadata_naming_a_foreign_bundle_or_a_missing_type_is_refused(self):
        def foreign(files):
            doc = json.loads(files["Payload/Octo.app/Metadata.appintents/extract.actionsdata"])
            doc["actions"]["PlayItemIntent"]["mangledTypeNameByBundleIdentifier"]["com.other.app"] = "x"
            files["Payload/Octo.app/Metadata.appintents/extract.actionsdata"] = json.dumps(doc).encode()
        self.assertProblem(self.problems(edit=foreign), "not ours: com.other.app")

        def missing_type(files):
            doc = json.loads(files["Payload/Octo.app/Metadata.appintents/extract.actionsdata"])
            doc["queries"] = {}
            files["Payload/Octo.app/Metadata.appintents/extract.actionsdata"] = json.dumps(doc).encode()
        self.assertProblem(self.problems(edit=missing_type), "lacks ['OctoIntents.MusicItemQuery']")

    def test_metadata_for_a_type_the_binary_lacks_is_refused(self):
        saved = set(SYMBOLS[asc.APP_ID + ".widgets"])
        SYMBOLS[asc.APP_ID + ".widgets"].discard("_$s11OctoIntents14MusicItemQueryVMn")
        try:
            self.assertProblem(self.problems(), "which the app.winters.octo.widgets binary does not define")
        finally:
            SYMBOLS[asc.APP_ID + ".widgets"] = saved

    def test_metadata_where_none_belongs_is_refused(self):
        def extra(files):
            files["Payload/Octo.app/PlugIns/OctoShare.appex/Metadata.appintents/extract.actionsdata"] = b"{}"
        self.assertProblem(self.problems(edit=extra), "where none belongs")

    def test_missing_widget_metadata_is_refused(self):
        def gone(files):
            for name in list(files):
                if "OctoWidgets.appex/Metadata.appintents" in name:
                    files[name] = None
        self.assertProblem(self.problems(edit=gone), "app.winters.octo.widgets: no App Intents metadata")

    def test_privacy_manifest_rules(self):
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/PrivacyInfo.xcprivacy": None})),
                           "no PrivacyInfo.xcprivacy")
        ipa = make_ipa(Path(self.temp.name) / "x.ipa", self.tree)
        problems = asc.validate_problems(self.tree, ipa, False, DT, CERT, symbols=fake_symbols, demangler=fake_demangle,
                                         scan=lambda p: {"NSPrivacyAccessedAPICategoryDiskSpace"})
        self.assertProblem(problems, "lacks ['NSPrivacyAccessedAPICategoryDiskSpace']")

    def test_the_encryption_answer_is_required(self):
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/Info.plist"].pop("ITSAppUsesNonExemptEncryption")),
                           "ITSAppUsesNonExemptEncryption")

    def test_carriage_returns_in_the_tree_are_refused(self):
        (self.tree / "ios/xtool.yml").write_bytes(b"version: 1\r\n")
        store_inputs.record(self.tree, "intents", self.tree / "ios/Resources/StoreAssets/Intents/inputs.json", "27A9275")
        self.assertProblem(self.problems(), "carriage returns in ios/xtool.yml")

    def test_what_the_build_wrote_and_binary_plists_are_not_read_as_text(self):
        # The build's own output (a resource bundle's binary Info.plist with a 0x0D byte in it)
        # is not source, and a binary plist anywhere has no line endings to check.
        built = self.tree / "ios/.build/out/Products/Release-iphoneos/Octo_Octo.bundle/Info.plist"
        built.parent.mkdir(parents=True)
        built.write_bytes(b"bplist00\x0d\x01")
        (self.tree / "ios/xtool/Octo.app").mkdir(parents=True)
        (self.tree / "ios/xtool/Octo.app/Info.plist").write_bytes(b"text\r\n")
        (self.tree / "ios/Settings.plist").write_bytes(b"bplist00\x0d")
        self.assertEqual(asc.cr_problems(self.tree), [])
        # A text plist with Windows line endings in the source is still refused.
        (self.tree / "ios/Text.plist").write_bytes(b"<plist>\r\n</plist>\r\n")
        self.assertEqual(asc.cr_problems(self.tree), ["carriage returns in ios/Text.plist"])

    def test_metadata_from_another_xcode_is_refused(self):
        def other_tools(files):
            files["Payload/Octo.app/Metadata.appintents/version.json"] = b'{"version":"3.0","toolsVersion":"17F113"}'
        self.assertProblem(self.problems(edit=other_tools), "written by Xcode 17F113")

    def test_the_install_source_key_belongs_only_in_the_sideload_ipa(self):
        self.assertProblem(self.problems(edit=lambda f: f["Payload/Octo.app/Info.plist"].update({asc.SOURCE_KEY: "sideload"})),
                           "must not carry OctoInstallSource")
        self.assertProblem(self.problems(sideload=True, edit=lambda f: f["Payload/Octo.app/Info.plist"].pop(asc.SOURCE_KEY)),
                           "lacks OctoInstallSource")

    def test_a_sideload_ipa_with_a_profile_is_refused(self):
        def with_profile(files):
            files["Payload/Octo.app/embedded.mobileprovision"] = profile(asc.BUNDLES[0])
        self.assertProblem(self.problems(sideload=True, edit=with_profile), "the sideload IPA carries a profile")

    def test_a_non_arm64_or_unsigned_executable_is_refused(self):
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/Octo": macho(expected(asc.BUNDLES[0]), arch=7)})),
                           "not arm64 only")
        self.assertProblem(self.problems(edit=lambda f: f.update({"Payload/Octo.app/_CodeSignature/CodeResources": None})),
                           "not signed")


class IntentsSourceTests(Home):
    def test_declared_types_include_queries_and_extensions_and_skip_packages(self):
        (self.tree / "ios/Sources/OctoIntents/More.swift").write_text(
            "import AppIntents\nextension Late: AppEnum {}\n@available(iOS 26, *)\npublic struct Later: EntityPropertyQuery {}\n")
        found = asc.declared_intents(self.tree / "ios", ["OctoIntents", "Octo"])
        self.assertEqual(found, {"OctoIntents.MusicItemEntity", "OctoIntents.MusicItemQuery", "OctoIntents.PlayItemIntent",
                                 "OctoIntents.Late", "OctoIntents.Later", "Octo.OctoShortcuts"})

    def test_an_unknown_app_intents_protocol_stops_the_check(self):
        (self.tree / "ios/Sources/OctoIntents/New.swift").write_text("import AppIntents\nstruct Snap: CameraCaptureIntent {}\n")
        with self.assertRaises(asc.Refused):
            asc.declared_intents(self.tree / "ios", ["OctoIntents"])

    def test_metadata_pairs_and_names_read_the_top_level_entries_only(self):
        doc = metadata_doc([asc.APP_ID], with_provider=True)
        pairs = asc.metadata_pairs(doc, asc.APP_ID)
        self.assertIn((asc.APP_ID, "4Octo0A9ShortcutsV"), pairs)
        self.assertNotIn((asc.APP_ID, "SS"), pairs)
        self.assertEqual(asc.metadata_names(doc, fake_demangle), {
            "OctoIntents.PlayItemIntent", "OctoIntents.MusicItemEntity", "OctoIntents.MusicItemQuery", "Octo.OctoShortcuts"})

    def test_the_canaries_catch_a_check_that_sees_nothing(self):
        docs = {asc.APP_ID: metadata_doc([asc.APP_ID], with_provider=True)}
        declared = {asc.APP_ID: asc.declared_intents(self.tree / "ios", ["OctoIntents", "Octo"])}
        self.assertEqual(asc.canary_problems(docs, declared, SYMBOLS, fake_demangle), [])
        real = asc.metadata_problems
        asc.metadata_problems = lambda *a, **k: []
        try:
            problems = asc.canary_problems(docs, declared, SYMBOLS, fake_demangle)
        finally:
            asc.metadata_problems = real
        for kind in ("actions", "entities", "queries"):
            self.assertTrue(any(f"renamed {kind}" in p for p in problems), problems)
            self.assertTrue(any(f"dropped {kind}" in p for p in problems), problems)

    def test_the_v2_names_and_the_provider_are_checked_against_the_right_binary(self):
        doc = metadata_doc([asc.APP_ID + ".widgets"], with_provider=True)
        doc["actions"]["PlayItemIntent"]["mangledTypeNameByBundleIdentifierV2"][asc.APP_ID + ".widgets"] = "11OctoIntents4GoneV"
        pairs = asc.metadata_pairs(doc, asc.APP_ID + ".widgets")
        self.assertIn((asc.APP_ID + ".widgets", "11OctoIntents4GoneV"), pairs)
        self.assertIn((asc.APP_ID, "4Octo0A9ShortcutsV"), pairs)

    def test_macro_intents_and_retroactive_conformances(self):
        (self.tree / "ios/Sources/OctoIntents/Late.swift").write_text(
            "import AppIntents\nextension Song: @retroactive AppEntity {}\n")
        self.assertIn("OctoIntents.Song", asc.declared_intents(self.tree / "ios", ["OctoIntents"]))
        (self.tree / "ios/Sources/OctoIntents/Macro.swift").write_text(
            "import AppIntents\n@AppIntent(schema: .photos.openAsset)\nstruct OpenIt {}\n")
        with self.assertRaises(asc.Refused):
            asc.declared_intents(self.tree / "ios", ["OctoIntents"])

    def test_the_package_must_build_each_bundle_from_the_listed_intents_modules(self):
        self.assertEqual(asc.package_problems(self.tree / "ios"), [])
        package = self.tree / "ios/Package.swift"
        package.write_text(package.read_text().replace('.target(name: "OctoWidgets", dependencies: ["OctoSystem", "OctoIntents"]',
                                                       '.target(name: "OctoWidgets", dependencies: ["OctoSystem"]'))
        self.assertTrue(any("app.winters.octo.widgets" in p for p in asc.package_problems(self.tree / "ios")))


def builds_route(versions):
    return {"data": [{"id": f"B{v}", "attributes": {"version": str(v), "processingState": "VALID",
                                                     "expirationDate": "2027-01-01T00:00:00Z"}} for v in versions]}


def profile_route(bundle_by_id):
    def answer(body, params, _cache={}):
        return {}
    return answer


class ReserveUploadTests(Home):
    def api(self, extra=()):
        routes = list(extra) + [
            ("GET", r"/v1/builds", builds_route([3, 5])),
            ("GET", r"/v1/apps/APP1/buildUploads", {"data": [{"attributes": {"cfBundleVersion": "6"}}]}),
        ]
        for b in asc.BUNDLES:
            content = __import__("base64").b64encode((asc.HOME / b["profile"]).read_bytes()).decode()
            routes.append(("GET", rf"/v1/profiles/PROF-{b['exe']}", {"data": {"id": f"PROF-{b['exe']}", "attributes": {
                "profileState": "ACTIVE", "profileContent": content}}}))
        return FakeApi(routes)

    def test_reserve_takes_one_past_every_number_seen(self):
        asc.save_json(asc.HOME / "burned.json", [9])
        api = self.api()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(asc.cmd_reserve(api, "b" * 40), 10)
        res = asc.load_json(asc.HOME / "reserved.json")
        self.assertEqual((res["build"], res["commit"], res["tree"]), (10, "b" * 40, "tree1234"))
        self.assertEqual(set(res["profiles"]), {b["id"] for b in asc.BUNDLES})
        self.assertEqual(api.methods(), ["GET"] * len(api.calls))

    def test_a_new_reserve_closes_and_burns_an_open_one(self):
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(self.api(), "b" * 40)
            self.assertEqual(asc.cmd_reserve(self.api(), "c" * 40), 8)
        self.assertEqual(asc.load_json(asc.HOME / "burned.json"), [7])

    def test_reserve_wants_a_full_commit_and_active_profiles(self):
        with self.assertRaises(asc.Refused):
            asc.cmd_reserve(self.api(), "main")
        api = self.api()  # App Store Connect holds the profiles as they are now
        (asc.HOME / "app.mobileprovision").write_bytes(b"changed")
        with self.assertRaises(asc.Refused), contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(api, "b" * 40)

    def test_reserve_refuses_a_store_build_older_than_its_apps_release(self):
        asc.release_versions = lambda ref: ("1.12.0", [1, 13])
        api = self.api()
        with self.assertRaises(asc.Refused), contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(api, "b" * 40)
        self.assertEqual(api.calls, [])
        self.assertIsNone(asc.load_json(asc.HOME / "reserved.json"))

    def built(self):
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(self.api(), "b" * 40)
        n = asc.load_json(asc.HOME / "reserved.json")["build"]
        dist = asc.WORK / "dist"
        dist.mkdir(exist_ok=True)

        def numbered(files):
            for name, data in files.items():
                if name.endswith("Info.plist") and isinstance(data, dict) and "CFBundleVersion" in data:
                    data["CFBundleVersion"] = str(n)
        signed = make_ipa(dist / f"Octo-1.11.0-{n}-bbbbbbbb.ipa", self.tree, edit=numbered)
        sideload = make_ipa(dist / f"Octo-1.11.0-{n}-bbbbbbbb-iPhone.ipa", self.tree, sideload=True, edit=numbered)
        nonce = asc.load_json(asc.HOME / "reserved.json")["nonce"]
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_record_built(signed, sideload, nonce)
        return signed

    def test_record_built_belongs_to_one_reservation(self):
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(self.api(), "b" * 40)
        dist = asc.WORK / "dist"
        dist.mkdir(exist_ok=True)
        n = asc.load_json(asc.HOME / "reserved.json")["build"]

        def numbered(files):
            for name, data in files.items():
                if name.endswith("Info.plist") and isinstance(data, dict) and "CFBundleVersion" in data:
                    data["CFBundleVersion"] = str(n)
        signed = make_ipa(dist / "s.ipa", self.tree, edit=numbered)
        side = make_ipa(dist / "i.ipa", self.tree, sideload=True, edit=numbered)
        with self.assertRaises(asc.Refused):
            asc.cmd_record_built(signed, side, "another reservation")
        wrong = make_ipa(dist / "w.ipa", self.tree, edit=lambda f: [d.update(CFBundleVersion="99") for n, d in f.items()
                                                                    if n.endswith("Info.plist") and isinstance(d, dict) and "CFBundleVersion" in d])
        with self.assertRaises(asc.Refused):
            asc.cmd_record_built(wrong, side, asc.load_json(asc.HOME / "reserved.json")["nonce"])

    def test_a_cut_off_upload_apple_has_is_recorded_not_burned(self):
        signed = self.built()
        res = asc.load_json(asc.HOME / "reserved.json")
        res.update(state="uploading", upload_id="UP1")
        asc.save_json(asc.HOME / "reserved.json", res)
        has = FakeApi(self.api().routes[:1] + [("GET", r"/v1/builds", lambda b, p: builds_route([7]) if p.get("filter[version]") == "7"
                                                 else builds_route([3, 5]))] + self.api().routes)
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(has, "c" * 40)
        self.assertEqual([u["build"] for u in asc.load_json(asc.HOME / "uploads.json")], [7])
        self.assertTrue((asc.WORK / "builds/1.11.0-7" / signed.name).exists())
        self.assertNotIn(7, asc.load_json(asc.HOME / "burned.json", []))

    def test_a_cut_off_upload_apple_lacks_is_burned(self):
        self.built()
        res = asc.load_json(asc.HOME / "reserved.json")
        res.update(state="uploading", upload_id="UP1")
        asc.save_json(asc.HOME / "reserved.json", res)
        lacks = FakeApi([("GET", r"/v1/builds", lambda b, p: {"data": []} if p.get("filter[version]") else builds_route([3, 5]))]
                        + self.api().routes)
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_reserve(lacks, "c" * 40)
        self.assertEqual(asc.load_json(asc.HOME / "burned.json"), [7])
        self.assertFalse((asc.WORK / "builds/1.11.0-7").exists())

    def test_an_upload_under_way_blocks_another(self):
        signed = self.built()
        res = asc.load_json(asc.HOME / "reserved.json")
        res["state"] = "uploading"
        asc.save_json(asc.HOME / "reserved.json", res)
        with self.assertRaises(asc.Refused):
            asc.cmd_upload(self.upload_api(), signed, wait=False, send_parts=lambda ipa, ops: None)

    def test_only_an_uploaded_build_can_be_marked_checked(self):
        with self.assertRaises(asc.Refused), contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_mark_checked(7)
        signed = self.built()
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_upload(self.upload_api(), signed, wait=False, send_parts=lambda ipa, ops: None)
            asc.cmd_mark_checked(7)
        self.assertEqual(asc.load_json(asc.HOME / "checked.json"), [7])

    def upload_api(self, fail_parts=False, state="AWAITING_UPLOAD"):
        return self.api(extra=[
            ("POST", r"/v1/buildUploads", {"data": {"id": "UP1"}}),
            ("POST", r"/v1/buildUploadFiles", {"data": {"id": "F1", "attributes": {"uploadOperations": [{"offset": 0}]}}}),
            ("PATCH", r"/v1/buildUploadFiles/F1", {}),
            ("GET", r"/v1/buildUploads/UP1", {"data": {"attributes": {"state": {"state": state}}}}),
            ("DELETE", r"/v1/buildUploads/UP1", {}),
        ])

    def test_an_unreserved_ipa_is_refused(self):
        self.built()
        other = make_ipa(Path(self.temp.name) / "other.ipa", self.tree,
                         edit=lambda f: f.update({"Payload/Octo.app/extra.txt": b"not the validated build"}))
        api = self.upload_api()
        with self.assertRaises(asc.Refused):
            asc.cmd_upload(api, other, wait=False, send_parts=lambda ipa, ops: None)
        self.assertNotIn("POST", api.methods())

    def test_a_good_upload_closes_the_reservation_and_keeps_the_build(self):
        signed = self.built()
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_upload(self.upload_api(), signed, wait=False, send_parts=lambda ipa, ops: None)
        self.assertFalse((asc.HOME / "reserved.json").exists())
        uploads = asc.load_json(asc.HOME / "uploads.json")
        self.assertEqual((uploads[0]["build"], uploads[0]["commit"]), (7, "b" * 40))
        kept = asc.WORK / "builds/1.11.0-7"
        self.assertTrue((kept / "Octo-1.11.0-7-bbbbbbbb.ipa").exists())
        self.assertTrue((kept / "Octo-1.11.0-7-bbbbbbbb-iPhone.ipa").exists())
        self.assertEqual((asc.HOME / "last-build").read_text().strip(), "7")

    def test_a_failed_upload_burns_its_number_unless_apple_has_it(self):
        signed = self.built()

        def broken(ipa, ops):
            raise asc.Refused("network gone")
        with self.assertRaises(asc.Refused), contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_upload(self.upload_api(), signed, wait=False, send_parts=broken)
        self.assertEqual(asc.load_json(asc.HOME / "burned.json"), [7])
        self.assertFalse((asc.HOME / "reserved.json").exists())

        signed = self.built()
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_upload(self.upload_api(state="PROCESSING"), signed, wait=False, send_parts=broken)
        self.assertEqual(asc.load_json(asc.HOME / "uploads.json")[0]["build"], 8)


class SetupTests(Home):
    def test_a_dry_run_only_reads(self):
        api = FakeApi([("GET", r"/v1/bundleIds", {"data": [], "included": []})])
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_setup_ids(api, apply=False)
        self.assertEqual(set(api.methods()), {"GET"})

    def test_apply_registers_ids_and_capabilities(self):
        api = FakeApi([("GET", r"/v1/bundleIds", {"data": [], "included": []}),
                       ("POST", r"/v1/bundleIds", lambda body, p: {"data": {"id": "PK-" + body["data"]["attributes"]["identifier"]}}),
                       ("POST", r"/v1/bundleIdCapabilities", {"data": {}})])
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_setup_ids(api, apply=True)
        caps = [c[2]["data"]["attributes"]["capabilityType"] for c in api.calls if c[1] == "/v1/bundleIdCapabilities"]
        self.assertEqual(sorted(caps), ["APP_GROUPS", "APP_GROUPS", "APP_GROUPS", "SIRIKIT"])

    def profile_api(self, made_without_group=False):
        b64 = __import__("base64").b64encode
        made = []

        def create(body, params):
            identifier = next(b for b in asc.BUNDLES if self.cfg["bundles"][b["id"]] == body["data"]["relationships"]["bundleId"]["data"]["id"])
            made.append(identifier["id"])
            content = profile(identifier, group=not made_without_group)
            return {"data": {"id": f"NEW-{identifier['exe']}", "attributes": {"profileContent": b64(content).decode(),
                                                                              "expirationDate": "2027-10-18"}}}
        return FakeApi([("GET", r"/v1/apps", {"data": [{"id": "APP1", "attributes": {"bundleId": asc.APP_ID}}]}),
                        ("GET", r"/v1/profiles/.*", {"data": None}),
                        ("POST", r"/v1/profiles", create),
                        ("DELETE", r"/v1/profiles/.*", {})])

    def test_a_new_profile_without_the_group_is_deleted_and_named(self):
        api = self.profile_api(made_without_group=True)
        with self.assertRaises(asc.Refused) as caught, contextlib.redirect_stdout(io.StringIO()):
            asc.setup_profiles(api, apply=True, refresh=False)
        self.assertIn("App Group", str(caught.exception))
        deleted = [c[1] for c in api.calls if c[0] == "DELETE"]
        self.assertEqual(deleted, ["/v1/profiles/NEW-Octo"])

    def test_refresh_saves_the_new_profile_before_deleting_the_old(self):
        api = self.profile_api()
        with contextlib.redirect_stdout(io.StringIO()):
            asc.setup_profiles(api, apply=True, refresh=True)
        order = [(c[0], c[1]) for c in api.calls if c[0] in ("POST", "DELETE")]
        self.assertEqual(order[:2], [("POST", "/v1/profiles"), ("DELETE", "/v1/profiles/PROF-Octo")])
        self.assertEqual(asc.load_json(asc.HOME / "config.json")["profiles"][asc.APP_ID]["id"], "NEW-Octo")

    def test_testflight_groups_are_closed_to_mac_and_vision(self):
        api = FakeApi([("GET", r"/v1/apps/APP1/betaGroups", {"data": []}),
                       ("POST", r"/v1/betaGroups", lambda b, p: {"data": {"id": "G-" + b["data"]["attributes"]["name"], "attributes": {}}}),
                       ("PATCH", r"/v1/betaGroups/.*", {})])
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_setup_testflight(api, apply=True)
        made = {c[2]["data"]["attributes"]["name"]: c[2]["data"]["attributes"] for c in api.calls if c[0] == "POST"}
        self.assertTrue(made["Team"]["isInternalGroup"] and made["Team"]["hasAccessToAllBuilds"])
        self.assertFalse(made["Public"]["isInternalGroup"] or made["Public"]["publicLinkEnabled"])
        patched = [c[2]["data"]["attributes"] for c in api.calls if c[0] == "PATCH"]
        self.assertEqual(len(patched), 2)
        self.assertTrue(all(p == {"iosBuildsAvailableForAppleSiliconMac": False, "iosBuildsAvailableForAppleVision": False}
                            for p in patched))


class PublishTests(Home):
    PASSWORD = 'p&a%s"s\\é w0rd/+'

    def base_routes(self, state="", in_group=False, review=None, public=None):
        review = review or {"contactFirstName": "A", "contactLastName": "B", "contactPhone": "1", "contactEmail": "a@b.test",
                            "demoAccountRequired": True, "demoAccountName": "reviewer", "demoAccountPassword": self.PASSWORD,
                            "notes": "Sign in at https://demo.example.test with the account above."}
        public = public or {"iosBuildsAvailableForAppleSiliconMac": False, "iosBuildsAvailableForAppleVision": False,
                            "publicLinkEnabled": False}
        return [
            ("GET", r"/v1/builds", builds_route([1])),
            ("GET", r"/v1/apps/APP1/betaAppLocalizations", {"data": [{"attributes": {"locale": "en-US", "description": "d",
                                                                                     "feedbackEmail": "f@x.test"}}]}),
            ("GET", r"/v1/apps/APP1/betaAppReviewDetail", {"data": {"attributes": review}}),
            ("GET", r"/v1/betaGroups/G-PUBLIC", {"data": {"attributes": public}}),
            ("GET", r"/v1/betaGroups/G-PUBLIC/builds", {"data": [{"id": "B1"}] if in_group else []}),
            ("GET", r"/v1/builds/B1/buildBetaDetail", {"data": {"attributes": {"externalBuildState": state}}}),
            ("POST", r"/v1/betaGroups/G-PUBLIC/relationships/builds", {}),
            ("POST", r"/v1/betaAppReviewSubmissions", {"data": {}}),
            ("PATCH", r"/v1/betaGroups/G-PUBLIC", {"data": {"attributes": {"publicLinkEnabled": True,
                                                                           "publicLink": "https://testflight.apple.com/join/X"}}}),
        ]

    def setUp(self):
        super().setUp()
        asc.save_json(asc.HOME / "uploads.json", [{"build": 1, "commit": "b" * 40}])

    def checks(self, ping=True, mode=True, ancestor=True):
        return {"ping": lambda h, u, p: ping, "mode": lambda h, u, p: mode, "is_ancestor": lambda a, b: ancestor}

    def test_external_submits_once_and_reruns_skip_what_is_done(self):
        api = FakeApi(self.base_routes())
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_external(api, 1, **self.checks())
        self.assertEqual([c[1] for c in api.calls if c[0] == "POST"],
                         ["/v1/betaGroups/G-PUBLIC/relationships/builds", "/v1/betaAppReviewSubmissions"])
        again = FakeApi(self.base_routes(state="WAITING_FOR_BETA_REVIEW", in_group=True))
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_external(again, 1, **self.checks())
        self.assertNotIn("POST", again.methods())

    def test_external_refuses_until_the_stream_only_check_exists(self):
        api = FakeApi(self.base_routes())
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with self.assertRaises(asc.Refused) as caught:
                asc.cmd_external(api, 1, ping=lambda h, u, p: True, is_ancestor=lambda a, b: True)
        self.assertIn("Stream only check not built", str(caught.exception))
        self.assertNotIn("POST", api.methods())

    def test_external_preconditions(self):
        cases = [
            (dict(ping=False), "cannot sign in"),
            (dict(mode=False), "not set to Stream only"),
            (dict(ancestor=False), "does not contain"),
        ]
        for checks, text in cases:
            api = FakeApi(self.base_routes())
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(asc.Refused):
                    asc.cmd_external(api, 1, **self.checks(**checks))
            self.assertNotIn("POST", api.methods(), text)
        cfg = asc.load_config()
        build = builds_route([1])["data"][0]
        no_sign_in = FakeApi(self.base_routes(review={"demoAccountRequired": False}))
        problems = asc.external_problems(no_sign_in, cfg, build, "b" * 40, **self.checks())
        self.assertTrue(any("sign-in is required" in p for p in problems))
        open_mac = FakeApi(self.base_routes(public={"iosBuildsAvailableForAppleSiliconMac": True}))
        self.assertTrue(any("Macs or Vision" in p for p in asc.external_problems(open_mac, cfg, build, "b" * 40, **self.checks())))
        cfg.pop("requires_commit")
        problems = asc.external_problems(FakeApi(self.base_routes()), cfg, build, "b" * 40, **self.checks())
        self.assertTrue(any("requires_commit" in p for p in problems))

    def test_no_form_of_the_demo_secrets_is_ever_printed(self):
        def leaky_ping(host, user, password):
            raise asc.Refused(f"failed for {user}:{password} at https://{host}/rest/ping.view?p={password.encode().hex()}")
        api = FakeApi(self.base_routes())
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                asc.cmd_external(api, 1, ping=leaky_ping, mode=lambda *a: True, is_ancestor=lambda a, b: True)
            except asc.Refused as e:
                print(asc.redact(str(e)))
            print(asc.redact(json.dumps(api.calls[2])))
            print(asc.redact(json.dumps({"demoAccountPassword": self.PASSWORD, "contactPhone": "555"})))
        text = out.getvalue() + err.getvalue()
        raw = self.PASSWORD.encode()
        import base64 as b64
        import urllib.parse as up
        for form in (self.PASSWORD, raw.hex(), b64.b64encode(raw).decode(), up.quote(self.PASSWORD, safe=""),
                     up.quote_plus(self.PASSWORD), json.dumps(self.PASSWORD)[1:-1], "enc:" + raw.hex(), "reviewer", "555"):
            self.assertNotIn(form, text)

    def test_set_review_names_a_main_commit_and_a_host(self):
        real = asc.git_is_ancestor
        asc.git_is_ancestor = lambda older, newer: older == "d" * 40
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                asc.cmd_set_review("d" * 40, "demo.example.org")
            cfg = asc.load_config()
            self.assertEqual((cfg["requires_commit"], cfg["demo_host"]), ("d" * 40, "demo.example.org"))
            for requires, host in (("e" * 40, "demo.example.org"), ("d" * 40, "https://demo.example.org"), ("main", "x.org")):
                with self.assertRaises(asc.Refused):
                    asc.cmd_set_review(requires, host)
        finally:
            asc.git_is_ancestor = real

    def test_whats_new_reads_before_it_writes(self):
        same = FakeApi([("GET", r"/v1/builds", builds_route([1])),
                        ("GET", r"/v1/builds/B1/betaBuildLocalizations",
                         {"data": [{"id": "L1", "attributes": {"locale": "en-US", "whatsNew": "Try the widgets."}}]})])
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_whats_new(same, 1, "Try the widgets.")
        self.assertEqual(set(same.methods()), {"GET"})
        changed = FakeApi(same.routes + [("PATCH", r"/v1/betaBuildLocalizations/L1", {})])
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_whats_new(changed, 1, "Try Siri.")
        self.assertIn("PATCH", changed.methods())

    def test_whats_new_words(self):
        self.assertEqual(asc.whats_new_problems("Try the widgets."), [])
        self.assertTrue(asc.whats_new_problems("x" * 4001))
        self.assertTrue(asc.whats_new_problems("Songs — now"))
        self.assertTrue(asc.whats_new_problems("Play a preview of any song"))
        self.assertTrue(asc.whats_new_problems("  "))

    def test_check_only_reads_and_open_link_needs_an_approval(self):
        api = FakeApi(self.base_routes(state="IN_BETA_REVIEW", in_group=True))
        with contextlib.redirect_stdout(io.StringIO()):
            asc.cmd_check(api, 1)
            with self.assertRaises(asc.Refused):
                asc.cmd_open_link(api)
        self.assertEqual(set(api.methods()), {"GET"})
        approved = FakeApi(self.base_routes(state="BETA_APPROVED", in_group=True))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            asc.cmd_open_link(approved)
        self.assertIn("https://testflight.apple.com/join/X", out.getvalue())

    def test_an_expired_or_unprocessed_build_is_refused(self):
        expired = builds_route([1])
        expired["data"][0]["attributes"]["expired"] = True
        with self.assertRaises(asc.Refused):
            asc.find_build(FakeApi([("GET", r"/v1/builds", expired)]), asc.load_config(), 1)

    def test_waiting_for_processing_is_set_in_seconds(self):
        processing = {"data": [{"attributes": {"processingState": "PROCESSING"}}]}
        api = FakeApi([("GET", r"/v1/builds", processing)])
        slept = []
        os.environ["OCTO_ASC_PROCESSING_SECONDS"] = "0.2"
        os.environ["OCTO_ASC_POLL_SECONDS"] = "0.05"
        try:
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(asc.Refused) as caught:
                asc.wait_processing(api, asc.load_config(), "1.11.0", "7",
                                    sleep=lambda s: (slept.append(s), time.sleep(s)))
        finally:
            del os.environ["OCTO_ASC_PROCESSING_SECONDS"], os.environ["OCTO_ASC_POLL_SECONDS"]
        self.assertIn("timed out", str(caught.exception))
        self.assertTrue(slept and all(s == 0.05 for s in slept), slept)


class StatusTests(Home):
    NOW = datetime.datetime(2026, 11, 1, tzinfo=datetime.timezone.utc)

    def builds(self, days, expired=()):
        return {"data": [{"attributes": {"version": str(i + 1), "processingState": "VALID", "expired": i in expired,
                                         "expirationDate": (self.NOW + datetime.timedelta(days=d, hours=1)).isoformat()}}
                         for i, d in enumerate(days)]}

    def api(self, public, team):
        return FakeApi([("GET", r"/v1/betaGroups/G-PUBLIC/builds", public), ("GET", r"/v1/builds", team)])

    def test_the_newest_live_build_per_group_counts(self):
        # An old Public build about to expire beside a new one: testers still have 80 days.
        _, fewest = asc.status_lines(self.api(self.builds([3, 80]), self.builds([80])), asc.load_config(), self.NOW)
        self.assertEqual(fewest, 80)

    def test_the_group_with_the_fewest_days_decides(self):
        _, fewest = asc.status_lines(self.api(self.builds([5]), self.builds([65])), asc.load_config(), self.NOW)
        self.assertEqual(fewest, 5)

    def test_expired_builds_are_ignored_and_a_group_with_none_live_reads_zero(self):
        _, fewest = asc.status_lines(self.api(self.builds([40, -3], expired=(1,)), self.builds([60])), asc.load_config(), self.NOW)
        self.assertEqual(fewest, 40)
        _, fewest = asc.status_lines(self.api(self.builds([-3], expired=(0,)), self.builds([60])), asc.load_config(), self.NOW)
        self.assertEqual(fewest, 0)

    def test_public_before_its_first_build_is_not_counted(self):
        _, fewest = asc.status_lines(self.api({"data": []}, self.builds([60])), asc.load_config(), self.NOW)
        self.assertEqual(fewest, 60)

    def test_brief_warns_at_twenty_one_days(self):
        for days, code in ((22, 0), (21, 2), (0, 2)):
            _, fewest = asc.status_lines(self.api(self.builds([days]), self.builds([80])), asc.load_config(), self.NOW)
            self.assertEqual(2 if fewest <= asc.EXPIRY_WARN_DAYS else 0, code, days)

    def test_brief_warns_when_the_certificate_is_close_to_expiring(self):
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        now = datetime.datetime.now(datetime.timezone.utc)
        live = {"data": [{"attributes": {"version": "1", "processingState": "VALID", "expired": False,
                                         "expirationDate": (now + datetime.timedelta(days=80)).isoformat()}}]}
        pem = Path(asc.load_config()["streamnook_config"]).parent / "dist.pem"
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(x509.oid.NameOID.COMMON_NAME, "Apple Distribution: Test")])
        for days, code in ((30, 0), (20, 2)):
            cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                    .serial_number(1).not_valid_before(now - datetime.timedelta(days=1))
                    .not_valid_after(now + datetime.timedelta(days=days, hours=1)).sign(key, hashes.SHA256()))
            pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(asc.cmd_status(self.api(live, live), brief=True), code, days)
        self.assertIn("certificate has 20 days left", out.getvalue())

    def test_a_failing_status_exits_non_zero(self):
        class Broken:
            def __init__(self, creds):
                pass

            def call(self, *a, **k):
                raise asc.Refused("no answer")
        real = asc.Api
        asc.Api = Broken
        try:
            with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(asc.main(["status", "--brief"]), 1)
        finally:
            asc.Api = real


class StageTests(Home):
    def unsigned(self):
        """An xtool-shaped unsigned IPA: loose icon, ad hoc signatures, iPad, placeholder DT keys."""
        path = Path(self.temp.name) / "unsigned.ipa"
        app = "Payload/Octo.app/"
        with zipfile.ZipFile(path, "w") as z:
            for b in asc.BUNDLES:
                root = app + (b["path"] + "/" if b["path"] else "")
                info = {"CFBundleIdentifier": b["id"], "CFBundleExecutable": b["exe"], "CFBundleVersion": "1",
                        "CFBundleShortVersionString": "1.11.0", "UIDeviceFamily": [1, 2], "DTXcode": "10989"}
                if not b["path"]:
                    info.update({"CFBundleIconFile": "AppIcon", "ITSAppUsesNonExemptEncryption": False})
                z.writestr(root + "Info.plist", plist(info))
                z.writestr(root + b["exe"], macho(expected(asc.BUNDLES[0], store=False)))
                z.writestr(root + "_CodeSignature/CodeResources", b"old")
            z.writestr(app + "AppIcon.png", b"loose")
            z.writestr(app + "Octo_Octo.bundle/Info.plist", plist({"CFBundleIdentifier": "r", "DTXcode": "10989"}))
        return path

    def test_stage_stamps_every_bundle_and_fills_the_store_assets(self):
        real = (asc.sdk_dt_keys, asc.scanned_categories)
        asc.sdk_dt_keys, asc.scanned_categories = (lambda: dict(DT)), (lambda p: {"NSPrivacyAccessedAPICategoryDiskSpace"})
        try:
            app = asc.stage(self.tree, self.unsigned(), 7, Path(self.temp.name) / "w", sideload=False)
            side = asc.stage(self.tree, self.unsigned(), 7, Path(self.temp.name) / "s", sideload=True)
        finally:
            asc.sdk_dt_keys, asc.scanned_categories = real
        info = plistlib.loads((app / "Info.plist").read_bytes())
        self.assertEqual((info["CFBundleVersion"], info["UIDeviceFamily"], info["DTXcode"], info["CFBundleIconName"]),
                         ("7", [1], "2710", "AppIcon"))
        self.assertNotIn("CFBundleIconFile", info)
        self.assertNotIn(asc.SOURCE_KEY, info)
        self.assertFalse((app / "AppIcon.png").exists())
        self.assertTrue((app / "Assets.car").exists())
        self.assertFalse(list(app.rglob("_CodeSignature")))
        widget = plistlib.loads((app / "PlugIns/OctoWidgets.appex/Info.plist").read_bytes())
        self.assertEqual((widget["CFBundleVersion"], widget["CFBundleSupportedPlatforms"]), ("7", ["iPhoneOS"]))
        self.assertTrue((app / "PlugIns/OctoWidgets.appex/Metadata.appintents/extract.actionsdata").exists())
        self.assertTrue((app / "Metadata.appintents/extract.actionsdata").exists())
        self.assertFalse((app / "PlugIns/OctoShare.appex/Metadata.appintents").exists())
        resource = plistlib.loads((app / "Octo_Octo.bundle/Info.plist").read_bytes())
        self.assertEqual(resource["DTXcode"], "2710")
        self.assertNotIn("CFBundleVersion", resource)
        manifest = plistlib.loads((app / "PlugIns/OctoShare.appex/PrivacyInfo.xcprivacy").read_bytes())
        self.assertIn("NSPrivacyAccessedAPICategoryDiskSpace", {t["NSPrivacyAccessedAPIType"] for t in manifest["NSPrivacyAccessedAPITypes"]})
        self.assertEqual(plistlib.loads((side / "Info.plist").read_bytes())[asc.SOURCE_KEY], "sideload")

    def sideload_only(self, build, built_from):
        """cmd_sideload with a build number and no reservation; the signer copies what it is given."""
        (asc.WORK / "unsigned.from").write_text(f"{built_from} tree1234\n")
        out = Path(self.temp.name) / "Octo-1.11.0-iPhone.ipa"
        signed = []

        def fake_zsign(args, work):
            signed.append(args)
            shutil.copy(args[-1], args[args.index("-o") + 1])

        real = (asc.sdk_dt_keys, asc.scanned_categories, asc.zsign)
        asc.sdk_dt_keys, asc.scanned_categories, asc.zsign = (lambda: dict(DT)), (lambda p: set()), fake_zsign
        try:
            asc.cmd_sideload(self.tree, self.unsigned(), out, build)
        finally:
            asc.sdk_dt_keys, asc.scanned_categories, asc.zsign = real
        return out, signed

    def test_a_sideload_only_build_needs_no_reservation(self):
        self.assertFalse((asc.HOME / "reserved.json").exists())
        out, signed = self.sideload_only(1234, self.tree.name)
        with zipfile.ZipFile(out) as z:
            info = plistlib.loads(z.read("Payload/Octo.app/Info.plist"))
            widget = plistlib.loads(z.read("Payload/Octo.app/PlugIns/OctoWidgets.appex/Info.plist"))
        self.assertEqual((info["CFBundleVersion"], info[asc.SOURCE_KEY]), ("1234", "sideload"))
        self.assertEqual(widget["CFBundleVersion"], "1234")
        self.assertEqual(len(signed), 1)
        self.assertEqual(signed[0][0], "-a")

    def test_a_sideload_only_build_of_another_commit_is_refused(self):
        with self.assertRaises(asc.Refused):
            self.sideload_only(1234, "c" * 40)

    def test_a_sideload_only_build_number_is_positive(self):
        with self.assertRaises(asc.Refused):
            self.sideload_only(0, self.tree.name)

    def test_a_sideload_without_a_build_still_needs_the_reservation(self):
        with self.assertRaises(asc.Refused):
            self.sideload_only(None, self.tree.name)


class RedactTests(unittest.TestCase):
    def test_hidden_fields_and_kept_secrets(self):
        asc.keep_secret("hunter22")
        text = asc.redact('{"demoAccountName": "x", "contactEmail": "e@x", "feedbackEmail": "f@x"} hunter22 '
                          + "hunter22".encode().hex())
        self.assertNotIn("hunter22", text)
        self.assertNotIn("e@x", text)
        self.assertNotIn("68756e7465723232", text)

    def test_an_uncaught_error_is_printed_redacted(self):
        asc.keep_secret("hunter22")
        saved = sys.excepthook
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                try:
                    asc.main(["entitlements", "/nonexistent.ipa"])
                except BaseException:
                    pass
            self.assertIs(sys.excepthook, asc.redacting_hook)
        finally:
            sys.excepthook = saved
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            asc.redacting_hook(ValueError, ValueError("sign-in failed for hunter22"), None)
        self.assertIn("ValueError", err.getvalue())
        self.assertNotIn("hunter22", err.getvalue())


STORE_INTENTS = HERE.parents[1] / "Resources/StoreAssets/Intents"


@unittest.skipUnless((STORE_INTENTS / "inputs.json").is_file(), "the store assets are not committed yet")
class CommittedMetadataTests(unittest.TestCase):
    """The App Intents checks run on the metadata the Mac workflow wrote, not only on fixtures
    shaped like it. Each type the metadata names counts as defined here; the binaries are
    checked by every validate."""

    def setUp(self):
        self.docs, self.declared, self.symbols = {}, {}, {}
        for b in asc.BUNDLES:
            if not b["intents"]:
                continue
            meta = STORE_INTENTS / b["exe"] / asc.store_inputs.METADATA / "extract.actionsdata"
            self.docs[b["id"]] = json.loads(meta.read_text(encoding="utf-8"))
            self.declared[b["id"]] = asc.declared_intents(HERE.parents[1], b["intents"])
        for bundle_id, doc in self.docs.items():
            for named, mangled in asc.metadata_pairs(doc, bundle_id):
                self.symbols.setdefault(named, set()).add(f"_$s{mangled}Mn")

    def test_every_declared_type_is_in_the_committed_metadata(self):
        self.assertTrue(all(self.declared.values()), self.declared)
        self.assertEqual(asc.metadata_problems(self.docs, self.declared, self.symbols), [])

    def test_the_canaries_trip_on_the_committed_metadata(self):
        for kind in ("actions", "entities", "queries"):
            self.assertTrue(any(doc.get(kind) for doc in self.docs.values()), kind)
        self.assertEqual(asc.canary_problems(self.docs, self.declared, self.symbols), [])

    def test_the_committed_metadata_came_from_the_recorded_xcode(self):
        build = json.loads((STORE_INTENTS / "inputs.json").read_text(encoding="utf-8"))["xcode_build"]
        for b in asc.BUNDLES:
            if b["intents"]:
                version = STORE_INTENTS / b["exe"] / asc.store_inputs.METADATA / "version.json"
                self.assertEqual(json.loads(version.read_text(encoding="utf-8")).get("toolsVersion"), build)


class ReleaseVersionTests(unittest.TestCase):
    """The store build's version against the apps release its commit carries, read from git."""

    def test_the_floor_is_the_newest_apps_minor_or_iphone_release(self):
        self.assertEqual(asc.release_floor(["android-v1.9.0", "desktop-v1.10.0", "v2026.11.13"]), [1, 10])
        self.assertEqual(asc.release_floor(["android-v1.12.1", "desktop-v1.12.0"]), [1, 12])
        self.assertEqual(asc.release_floor(["android-v1.12.0", "ios-v1.12.1"]), [1, 12, 1])
        self.assertEqual(asc.release_floor(["android-v1.12.0-rehearsal.1", "android-v1.11.0"]), [1, 11])
        self.assertIsNone(asc.release_floor(["2026.11.13", "android-v1.12.0-rehearsal.1"]))

    def test_a_store_build_may_lead_but_never_trail_the_apps(self):
        floor = lambda *tags: asc.release_floor(list(tags))
        asc.check_release_version("1.12.0", floor("android-v1.12.0", "desktop-v1.12.0"))
        asc.check_release_version("1.12.0", floor("android-v1.12.1"))
        asc.check_release_version("1.12.1", floor("desktop-v1.12.0"))
        for iphone, found in (("1.12.0", floor("android-v1.13.0")), ("1.9.0", floor("desktop-v1.10.0")),
                              ("0.1.0", floor("android-v1.12.0")), ("1.12.0", floor("ios-v1.12.1")),
                              ("1.12.0", None), ("", floor("android-v1.12.0"))):
            with self.assertRaises(asc.Refused, msg=f"{iphone} {found}"):
                asc.check_release_version(iphone, found)

    def test_the_versions_come_from_the_commit_and_its_reachable_tags(self):
        with tempfile.TemporaryDirectory() as temp:
            origin, repo = Path(temp) / "origin", Path(temp) / "repo"
            saved = asc.REPO
            asc.REPO = repo

            def run(where: Path, *args):
                return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", str(where), *args],
                                      check=True, capture_output=True, text=True).stdout.strip()

            def commit(version: str, *, share: str | None = None) -> str:
                (origin / "ios").mkdir(parents=True, exist_ok=True)
                for name in ("Info.plist", "OctoShare-Info.plist", "OctoWidgets-Info.plist"):
                    value = share if (share and name == "OctoShare-Info.plist") else version
                    (origin / "ios" / name).write_bytes(plist({"CFBundleShortVersionString": value}))
                run(origin, "add", "-A")
                run(origin, "commit", "-q", "-m", version)
                return run(origin, "rev-parse", "HEAD")

            try:
                origin.mkdir()
                run(origin, "init", "-q")
                first = commit("1.11.0")
                run(origin, "tag", "android-v1.11.0")
                second = commit("1.12.0")
                run(origin, "tag", "-a", "desktop-v1.12.0", "-m", "Notes.")
                third = commit("1.12.0", share="1.11.0")
                run(Path(temp), "clone", "-q", str(origin), str(repo))
                self.assertEqual(asc.release_versions(first), ("1.11.0", [1, 11]))
                self.assertEqual(asc.release_versions(second), ("1.12.0", [1, 12]))
                with self.assertRaises(asc.Refused):
                    asc.release_versions(third)
                # A release tagged on origin after the last fetch: refused until the tags are fetched.
                run(origin, "tag", "android-v1.13.0", second)
                with self.assertRaises(asc.Refused):
                    asc.release_versions(second)
                run(repo, "fetch", "-q", "origin", "--tags")
                self.assertEqual(asc.release_versions(second), ("1.12.0", [1, 13]))
                # A tag only this clone has (never pushed) would raise the floor: refused.
                run(repo, "tag", "desktop-v1.14.0", second)
                with self.assertRaises(asc.Refused):
                    asc.release_versions(second)
                run(repo, "tag", "-d", "desktop-v1.14.0")
                # A tag moved on origin: refused until the clone takes the moved one.
                run(origin, "tag", "-f", "android-v1.13.0", first)
                with self.assertRaises(asc.Refused):
                    asc.release_versions(second)
                run(repo, "fetch", "-q", "--force", "origin", "--tags")
                self.assertEqual(asc.release_versions(second), ("1.12.0", [1, 13]))
                # A release-like tag in a folder is not a release tag on either side.
                run(origin, "tag", "rel/ios-v9.0.0", second)
                self.assertEqual(asc.release_versions(second), ("1.12.0", [1, 13]))
                run(origin, "tag", "ios-v9.0.0/x", second)
                run(repo, "fetch", "-q", "origin", "--tags")
                self.assertEqual(asc.release_versions(second), ("1.12.0", [1, 13]))
            finally:
                asc.REPO = saved


if __name__ == "__main__":
    unittest.main()
