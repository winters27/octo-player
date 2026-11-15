"""Tests for store_inputs.py.

Run: python3 -I -m unittest discover -s ios/scripts/release -p 'test_*.py' -v
"""
import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("store_inputs", HERE / "store_inputs.py")
store_inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(store_inputs)


def tree(root: Path) -> Path:
    files = {
        "ios/AppIcon.icon/icon.json": "{}",
        "ios/AppIcon.icon/Assets/octopus.svg": "<svg/>",
        "ios/Launch.xcassets/Contents.json": "{}",
        "ios/Package.swift": "// swift-tools-version: 6.0",
        "ios/project.yml": "name: Octo",
        "ios/xtool.yml": "version: 1",
        "ios/Sources/OctoIntents/MusicIntents.swift": "import AppIntents\nstruct A: AppIntent {}",
        "ios/Sources/Octo/Player.swift": "import AVFoundation",
        ".github/workflows/ios-store-assets.yml": "name: iOS store assets",
    }
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return root


def refuses(step) -> bool:
    with redirect_stderr(io.StringIO()):
        try:
            step()
        except SystemExit:
            return True
    return False


class StoreInputsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = tree(Path(self.temp.name) / "repo")

    def tearDown(self):
        self.temp.cleanup()

    def test_icon_inputs_are_both_catalogs_and_the_workflow(self):
        self.assertEqual(store_inputs.files(self.root, "icon"), [
            ".github/workflows/ios-store-assets.yml",
            "ios/AppIcon.icon/Assets/octopus.svg",
            "ios/AppIcon.icon/icon.json",
            "ios/Launch.xcassets/Contents.json",
        ])

    def test_intents_inputs_are_the_manifests_the_workflow_and_app_intents_sources(self):
        self.assertEqual(store_inputs.files(self.root, "intents"), [
            ".github/workflows/ios-store-assets.yml",
            "ios/Package.swift",
            "ios/Sources/OctoIntents/MusicIntents.swift",
            "ios/project.yml",
            "ios/xtool.yml",
        ])

    def test_every_input_changes_the_hash(self):
        for kind in store_inputs.KINDS:
            names = store_inputs.files(self.root, kind)
            before = store_inputs.digest(self.root, names)
            for name in names:
                path = self.root / name
                old = path.read_bytes()
                path.write_bytes(old + b" ")
                self.assertNotEqual(store_inputs.digest(self.root, names), before, name)
                path.write_bytes(old)

    def test_a_source_without_app_intents_leaves_the_hash_alone(self):
        names = store_inputs.files(self.root, "intents")
        before = store_inputs.digest(self.root, names)
        (self.root / "ios/Sources/Octo/Player.swift").write_text("import AVFoundation\n// changed", encoding="utf-8")
        self.assertEqual(store_inputs.digest(self.root, store_inputs.files(self.root, "intents")), before)

    def test_the_framing_tells_moved_bytes_apart(self):
        # Unframed, both trees read "w" "x" "x": the byte moved from w into x.
        a, b = Path(self.temp.name) / "a", Path(self.temp.name) / "b"
        for root, (w, x) in ((a, ("x", "")), (b, ("", "x"))):
            root.mkdir()
            (root / "w").write_text(w)
            (root / "x").write_text(x)
        self.assertNotEqual(store_inputs.digest(a, ["w", "x"]), store_inputs.digest(b, ["w", "x"]))

    def test_the_framing_also_tells_moved_data_apart(self):
        # With only the paths framed, both trees read P"a" P"b" "Y" P"b" "Z": the data moved
        # across the second path, so only the data lengths can tell them apart.
        p = (1).to_bytes(8, "big")
        a, b = Path(self.temp.name) / "pa", Path(self.temp.name) / "pb"
        for root, (da, db) in ((a, (p + b"bY", b"Z")), (b, (b"", b"Y" + p + b"bZ"))):
            root.mkdir()
            (root / "a").write_bytes(da)
            (root / "b").write_bytes(db)
        self.assertNotEqual(store_inputs.digest(a, ["a", "b"]), store_inputs.digest(b, ["a", "b"]))

    def test_an_empty_icon_catalog_is_refused(self):
        for f in (self.root / "ios/AppIcon.icon").rglob("*"):
            if f.is_file():
                f.unlink()
        self.assertTrue(refuses(lambda: store_inputs.files(self.root, "icon")))

    def test_app_intents_in_the_xcode_support_folder_count(self):
        support = self.root / "ios/XcodeSupport/Extra.swift"
        support.parent.mkdir(parents=True)
        support.write_text("import AppIntents\n")
        self.assertIn("ios/XcodeSupport/Extra.swift", store_inputs.files(self.root, "intents"))

    def test_a_missing_input_is_refused(self):
        (self.root / "ios/xtool.yml").unlink()
        self.assertTrue(refuses(lambda: store_inputs.files(self.root, "intents")))
        (self.root / "ios/xtool.yml").write_text("version: 1")
        (self.root / ".github/workflows/ios-store-assets.yml").unlink()
        self.assertTrue(refuses(lambda: store_inputs.files(self.root, "icon")))

    def test_an_empty_launch_catalog_is_refused(self):
        (self.root / "ios/Launch.xcassets/Contents.json").unlink()
        self.assertTrue(refuses(lambda: store_inputs.files(self.root, "icon")))

    def test_collect_takes_exactly_the_expected_metadata(self):
        app = Path(self.temp.name) / "Octo.app"
        for where in ("Metadata.appintents", "PlugIns/OctoWidgets.appex/Metadata.appintents"):
            (app / where).mkdir(parents=True)
            (app / where / "extract.actionsdata").write_text("{}")
        out = Path(self.temp.name) / "out"
        entry = store_inputs.collect(self.root, app, out, "27A9275")
        self.assertTrue((out / "Octo/Metadata.appintents/extract.actionsdata").is_file())
        self.assertTrue((out / "OctoWidgets/Metadata.appintents/extract.actionsdata").is_file())
        self.assertEqual(json.loads((out / "inputs.json").read_text())["sha256"], entry["sha256"])
        self.assertEqual(entry["xcode_build"], "27A9275")

    def test_collect_refuses_missing_or_extra_metadata(self):
        app = Path(self.temp.name) / "Octo.app"
        (app / "Metadata.appintents").mkdir(parents=True)
        out = Path(self.temp.name) / "out"
        self.assertTrue(refuses(lambda: store_inputs.collect(self.root, app, out, "27A9275")))
        (app / "PlugIns/OctoWidgets.appex/Metadata.appintents").mkdir(parents=True)
        (app / "PlugIns/OctoShare.appex/Metadata.appintents").mkdir(parents=True)
        self.assertTrue(refuses(lambda: store_inputs.collect(self.root, app, out, "27A9275")))


if __name__ == "__main__":
    unittest.main()
