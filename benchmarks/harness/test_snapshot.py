# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import os
import subprocess
import tarfile
import tempfile
import unittest

from snapshot import freeze


class SnapshotTest(unittest.TestCase):
    def test_archived_dependencies_are_excluded_without_dropping_real_fixture_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            paths = ["agentscope-service/tests/session-playground/node_modules/pkg/index.js",
                     "agentscope-service/tests/session-playground/dist/index.html",
                     "agentscope-service/tests/session-playground/test-results/result.json",
                     "agentscope-service/tests/session-playground/src/main.ts",
                     "benchmarks/harness/datasets/example/dist/fixture.txt"]
            (root / "pom.xml").write_text("<project><properties><revision>test</revision></properties></project>")
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            for args in [("init",), ("add", "."),
                         ("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")]:
                subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)
            out = Path(temporary) / "out"
            receipt = freeze(root, out)
            self.assertEqual(receipt["source_exclusions"]["file_count"], 3)
            with tarfile.open(out / "candidate-source.tgz") as archive:
                for name in paths[:3]:
                    self.assertNotIn(name, archive.getnames())
                for name in paths[3:]:
                    self.assertIn(name, archive.getnames())

    @unittest.skipUnless(os.environ.get("HARNESS_EVAL_MAVEN_TEST"), "Opt-in offline Maven packaging check")
    def test_frozen_resources_are_copied_by_maven_plugin(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "fixture"
            root.mkdir()
            (root / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
                "<artifactId>resource-check</artifactId><version>1</version>"
                "<properties><revision>1</revision></properties></project>")
            resource = root / "src/main/resources/context.md"
            resource.parent.mkdir(parents=True)
            resource.write_text("required runtime instructions")
            for args in [("init",), ("add", "."),
                         ("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")]:
                subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)
            out = Path(temporary) / "archive"
            freeze(root, out)
            extracted = Path(temporary) / "unpacked"
            extracted.mkdir()
            with tarfile.open(out / "candidate-source.tgz") as tar:
                # This fixture has only these two regular files. Copy them explicitly
                # so Python versions before tarfile's filter API remain supported.
                for name in ("pom.xml", "src/main/resources/context.md"):
                    member = tar.getmember(name)
                    self.assertTrue(member.isfile())
                    path = extracted / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(tar.extractfile(member).read())
                    os.utime(path, (member.mtime, member.mtime))
            result = subprocess.run(
                ["mvn", "-o", "-B", "-T1", "org.apache.maven.plugins:maven-resources-plugin:3.3.1:resources"],
                cwd=extracted, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout[-1500:])
            self.assertEqual((extracted / "target/classes/context.md").read_text(), resource.read_text())

    def test_packages_modified_tracked_files_and_excludes_untracked_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            def git(*args):
                subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)
            git("init")
            (root / "pom.xml").write_text("<project><properties><revision>test-SNAPSHOT</revision></properties></project>")
            (root / "source.txt").write_text("old")
            (root / "deleted.txt").write_text("delete me")
            git("add", ".")
            git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")
            (root / "source.txt").write_text("fixed")
            (root / "deleted.txt").unlink()
            (root / "unrelated.txt").write_text("do not package")
            out = Path(temporary) / "artifact"
            metadata = freeze(root, out)
            self.assertEqual(metadata["version"], "test-SNAPSHOT")
            self.assertIn("source.txt", metadata["modified_files"])
            with tarfile.open(out / "candidate-source.tgz") as tar:
                self.assertEqual(tar.extractfile("source.txt").read(), b"fixed")
                self.assertNotIn("deleted.txt", tar.getnames())
                self.assertNotIn("unrelated.txt", tar.getnames())
                self.assertNotIn(".git", tar.getnames())

class RefSnapshotTest(unittest.TestCase):
    def test_exact_tag_ignores_dirty_index_and_worktree_without_checkout(self):
        from snapshot import freeze_ref
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'repo';root.mkdir()
            def git(*args):return subprocess.check_output(['git','-C',str(root),*args],stderr=subprocess.DEVNULL)
            git('init');(root/'pom.xml').write_text('<project><properties><revision>1.0</revision></properties></project>')
            (root/'source.txt').write_text('release')
            git('add','.');git('-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit','-m','release');git('tag','v1')
            head=git('rev-parse','HEAD')
            (root/'source.txt').write_text('staged');git('add','source.txt');(root/'source.txt').write_text('working')
            before=git('status','--porcelain')
            metadata=freeze_ref(root,Path(temporary)/'snapshot','v1')
            self.assertEqual(metadata['base_sha'],head.decode().strip());self.assertEqual(metadata['mode'],'git-ref')
            with tarfile.open(Path(temporary)/'snapshot/candidate-source.tgz') as tar:
                self.assertEqual(tar.extractfile('source.txt').read(),b'release')
            self.assertEqual(git('status','--porcelain'),before);self.assertEqual((root/'source.txt').read_text(),'working')
            with self.assertRaises(ValueError):freeze_ref(root,Path(temporary)/'bad','--help')


if __name__ == "__main__":
    unittest.main()
