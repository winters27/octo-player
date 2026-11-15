"""Tests for the iPhone parts of release_assets.py, and the fixture the iPhone app's own tests read.

Run from the repository root:
  python3 -I -m unittest discover -s scripts/release -p 'test_release_assets.py' -v
  python3 -I scripts/release/test_release_assets.py --write-ios-fixture    (after a manifest change)
"""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import plistlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
spec = importlib.util.spec_from_file_location("release_assets", HERE / "release_assets.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

# A throwaway Ed25519 key for tests only: no app trusts it. Its signatures are deterministic, so
# the fixture below never changes unless the manifest does.
TEST_KEY = """-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIN8bQJAoryOV4Vbz5WYhutfNEPI/Hwntbrm8sapvIvEy
-----END PRIVATE KEY-----
"""
TEST_PUBLIC = "B7uL13u1MWGnL639nRABmwTegi5EBx8FCBnsrI+xwEs="
FIXTURE = ROOT / "ios/Tests/OctoKitTests/Settings/IOSManifestFixture.swift"
IPA_BYTES = b"an iPhone build"


def ipa_reading(version: str) -> bytes:
    """A minimal IPA whose app reads the given version."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr("Payload/Octo.app/Info.plist", plistlib.dumps({"CFBundleShortVersionString": version}))
    return out.getvalue()


def sign(body: bytes) -> str:
    with tempfile.TemporaryDirectory() as temp:
        key, data, sig = Path(temp) / "k.pem", Path(temp) / "d", Path(temp) / "s"
        key.write_text(TEST_KEY)
        data.write_bytes(body)
        subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", str(key), "-rawin", "-in", str(data), "-out", str(sig)],
                       check=True, capture_output=True)
        return base64.b64encode(sig.read_bytes()).decode()


def staged_release(folder: Path, version="1.11.0") -> tuple[bytes, str]:
    ipa = folder / "built.ipa"
    ipa.write_bytes(IPA_BYTES)
    out = folder / "release"
    release.stage_ios(version, ipa, out)
    ipa.unlink()
    manifest = release.write_manifest("ios", f"ios-v{version}", "- First iPhone release.", out).read_bytes()
    signature = sign(manifest)
    (out / release.SIGNATURE).write_text(signature)
    return manifest, signature


def fixture_text() -> str:
    with tempfile.TemporaryDirectory() as temp:
        manifest, signature = staged_release(Path(temp))
    return (
        "// Written by scripts/release/test_release_assets.py --write-ios-fixture: an iPhone release's\n"
        "// manifest as the release scripts publish it, signed with a throwaway test key no app trusts.\n"
        "enum IOSManifestFixture {\n"
        f'    static let tag = "ios-v1.11.0"\n'
        f'    static let manifest = "{base64.b64encode(manifest).decode()}"\n'
        f'    static let signature = "{signature}"\n'
        f'    static let publicKey = "{TEST_PUBLIC}"\n'
        f'    static let ipaSha256 = "{hashlib.sha256(IPA_BYTES).hexdigest()}"\n'
        "}\n"
    )


def refuses(step) -> bool:
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            step()
        except SystemExit:
            return True
    return False


@contextlib.contextmanager
def repo():
    """A throwaway git repository as the working folder, as release_notes and the tag checks expect."""
    with tempfile.TemporaryDirectory() as temp:
        here = os.getcwd()
        os.chdir(temp)
        try:
            def run(*args):
                return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()
            run("init", "-q")
            Path("ios").mkdir()
            Path("ios/a.swift").write_text("let a = 1\n")
            run("add", "-A")
            run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "ios: the first iPhone build")
            yield run
        finally:
            os.chdir(here)


class IOSReleaseTests(unittest.TestCase):
    def test_the_ipa_is_staged_and_described_for_phones(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest, _ = staged_release(Path(temp))
        data = json.loads(manifest)
        self.assertEqual((data["product"], data["tag"], data["version"]), ("ios", "ios-v1.11.0", "1.11.0"))
        self.assertEqual(data["assets"], [{"name": "Octo-1.11.0-iPhone.ipa", "sha256": hashlib.sha256(IPA_BYTES).hexdigest(),
                                           "size": len(IPA_BYTES), "os": "ios", "arch": "arm64", "kind": "ipa"}])

    def test_a_badly_named_ipa_is_refused(self):
        self.assertTrue(refuses(lambda: release.describe("Octo-1.11.0.ipa", "ios", "1.11.0")))
        self.assertTrue(refuses(lambda: release.describe("Octo-1.10.0-iPhone.ipa", "ios", "1.11.0")))

    def test_the_tag_lines_never_reach_the_notes(self):
        with repo() as git:
            sha = git("rev-parse", "HEAD")
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.11.0", "-m",
                f"The first iPhone release.\n\nipa-sha256: {'a' * 64}\nipa-commit: {sha}")
            notes = release.release_notes("ios", "ios-v1.11.0")
            self.assertEqual(notes, "The first iPhone release.")
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.11.1", "-m",
                f"ipa-sha256: {'a' * 64}\nipa-commit: {sha}")
            self.assertNotIn("ipa-", release.release_notes("ios", "ios-v1.11.1"))

    def test_the_carrier_must_be_the_ipa_and_commit_the_tag_names(self):
        with repo() as git:
            sha = git("rev-parse", "HEAD")
            ipa = Path("built.ipa")
            built = ipa_reading("1.11.0")
            ipa.write_bytes(built)
            good = hashlib.sha256(built).hexdigest()
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.11.0", "-m",
                f"Notes.\n\nipa-sha256: {good}\nipa-commit: {sha}")
            release.verify_carrier("ios-v1.11.0", ipa)
            ipa.write_bytes(b"another build")
            self.assertTrue(refuses(lambda: release.verify_carrier("ios-v1.11.0", ipa)))
            ipa.write_bytes(built)
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.11.1", "-m",
                f"Notes.\n\nipa-sha256: {good}\nipa-commit: {'b' * 40}")
            self.assertTrue(refuses(lambda: release.verify_carrier("ios-v1.11.1", ipa)))
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.11.2", "-m", "Notes only.")
            self.assertTrue(refuses(lambda: release.verify_carrier("ios-v1.11.2", ipa)))

    def test_the_carrier_app_must_read_the_tags_version(self):
        with repo() as git:
            sha = git("rev-parse", "HEAD")
            ipa = Path("built.ipa")
            built = ipa_reading("1.12.0")
            ipa.write_bytes(built)
            good = hashlib.sha256(built).hexdigest()
            git("-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "ios-v1.13.0", "-m",
                f"Notes.\n\nipa-sha256: {good}\nipa-commit: {sha}")
            self.assertTrue(refuses(lambda: release.verify_carrier("ios-v1.13.0", ipa)))
            self.assertEqual(release.ipa_version(ipa), "1.12.0")
            ipa.write_bytes(b"not a zip")
            self.assertTrue(refuses(lambda: release.ipa_version(ipa)))
            for bad in (b"<plist><dict><key>x", plistlib.dumps(["not", "a", "dict"])):
                out = io.BytesIO()
                with zipfile.ZipFile(out, "w") as archive:
                    archive.writestr("Payload/Octo.app/Info.plist", bad)
                ipa.write_bytes(out.getvalue())
                self.assertTrue(refuses(lambda: release.ipa_version(ipa)))

    def test_a_published_release_is_checked_against_the_keys_the_app_trusts(self):
        with tempfile.TemporaryDirectory() as temp:
            staged_release(Path(temp))
            swift = Path(temp) / "PlayerUpdates.swift"
            swift.write_text(f'    public static let trustedKeysText = """\n    # a comment\n    {TEST_PUBLIC}\n    """\n')
            good = hashlib.sha256(IPA_BYTES).hexdigest()
            with contextlib.redirect_stdout(io.StringIO()):
                release.verify_release("ios-v1.11.0", Path(temp) / "release", swift, good)
            self.assertTrue(refuses(lambda: release.verify_release("ios-v1.11.0", Path(temp) / "release", swift, "c" * 64)))
            other = base64.b64encode(bytes(range(32))).decode()
            swift.write_text(f'    public static let trustedKeysText = """\n    {other}\n    """\n')
            self.assertTrue(refuses(lambda: release.verify_release("ios-v1.11.0", Path(temp) / "release", swift, good)))

    def test_the_iphone_trusts_the_same_keys_the_release_checks(self):
        shared = (ROOT / "shared/core/src/commonMain/resources/app/winters/octo/update/trusted-keys.txt").read_text()
        swift = release.swift_keys(ROOT / "ios/Sources/OctoKit/Settings/PlayerUpdates.swift")
        self.assertEqual(sorted(release.key_lines(shared)), sorted(release.key_lines(swift)))

    def test_the_iphone_fixture_is_current(self):
        self.assertEqual(FIXTURE.read_text(encoding="utf-8"), fixture_text(),
                         "run: python3 -I scripts/release/test_release_assets.py --write-ios-fixture")


if __name__ == "__main__":
    if sys.argv[1:] == ["--write-ios-fixture"]:
        FIXTURE.write_text(fixture_text(), encoding="utf-8", newline="\n")
        print("wrote", FIXTURE.relative_to(ROOT))
    else:
        unittest.main()
