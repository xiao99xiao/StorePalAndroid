import contextlib
import io
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from stage_repository import stage


class StageRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.destination = self.root / "pages"

    def version(self, value, content=b"artifact"):
        for artifact in ("storepal-core", "storepal-android"):
            path = self.source / "app" / "storepal" / artifact / value
            path.mkdir(parents=True, exist_ok=True)
            (path / f"{artifact}-{value}.pom").write_bytes(content)

    def publish(self):
        with contextlib.redirect_stdout(io.StringIO()):
            stage(self.source, self.destination)

    def test_preserves_old_versions_and_merges_metadata(self):
        self.version("0.1.0")
        self.publish()
        self.version("0.2.0")
        self.publish()
        base = self.destination / "maven/app/storepal/storepal-core"
        self.assertEqual(b"artifact", (base / "0.1.0/storepal-core-0.1.0.pom").read_bytes())
        metadata = ET.parse(base / "maven-metadata.xml")
        self.assertEqual(["0.1.0", "0.2.0"], [item.text for item in metadata.findall("versioning/versions/version")])
        self.assertEqual("0.2.0", metadata.findtext("versioning/release"))
        self.assertTrue((base / "maven-metadata.xml.sha256").is_file())

    def test_refuses_to_overwrite_a_released_version(self):
        self.version("0.1.0")
        self.publish()
        self.version("0.1.0", b"changed")
        with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
            self.publish()

    def test_prerelease_does_not_replace_the_stable_release_pointer(self):
        self.version("0.1.0")
        self.version("0.2.0-rc.1")
        self.publish()
        metadata = ET.parse(self.destination / "maven/app/storepal/storepal-core/maven-metadata.xml")
        self.assertEqual("0.2.0-rc.1", metadata.findtext("versioning/latest"))
        self.assertEqual("0.1.0", metadata.findtext("versioning/release"))


if __name__ == "__main__":
    unittest.main()
