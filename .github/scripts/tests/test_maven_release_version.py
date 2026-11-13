# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-maven-release-version.py"
spec = importlib.util.spec_from_file_location("maven_release_version", SCRIPT)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class MavenReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write_poms(self, revision, project_version="${revision}"):
        for filename in release.POMS:
            pom = self.root / filename
            pom.parent.mkdir(parents=True, exist_ok=True)
            pom.write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<version>{project_version}</version>"
                f"<properties><revision>{revision}</revision></properties>"
                "</project>",
                encoding="utf-8",
            )

    def test_stable_and_prerelease_tags(self):
        for version in ("2.1.0", "2.1.0-BETA2", "2.1.0-RC1", "2.0.5"):
            with self.subTest(version=version):
                self.write_poms(version)
                self.assertEqual(release.check_version(self.root, f"v{version}"), version)

    def test_reject_snapshot_and_unrelated_tags(self):
        for tag in (
            "v2.1.0-SNAPSHOT", "v2.1.0-snapshot", "2.1.0-BETA2", "v2.1",
            "library-v2.1.0-BETA2",
            "library/component/v2.1.0-BETA2",
            "v2.1.0-BETA2\n", "v2.1.0;echo hello",
        ):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.check_version(self.root, tag)

    def test_reject_each_independent_revision_mismatch(self):
        for filename in release.POMS:
            with self.subTest(pom=filename):
                self.write_poms("2.1.0-BETA2")
                pom = self.root / filename
                pom.write_text(pom.read_text().replace("2.1.0-BETA2", "2.1.0-SNAPSHOT"))
                with self.assertRaisesRegex(ValueError, "does not match tag"):
                    release.check_version(self.root, "v2.1.0-BETA2")

    def test_reject_project_version_not_using_revision(self):
        self.write_poms("2.1.0-BETA2", project_version="2.1.0-SNAPSHOT")
        with self.assertRaisesRegex(ValueError, "must use"):
            release.check_version(self.root, "v2.1.0-BETA2")

    def test_exclude_entire_examples_and_model_e2e_trees(self):
        self.write_poms("2.1.0-BETA2")
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
            '<module>library</module><module>agentscope-examples</module>'
            '<module>agentscope-extensions/agentscope-extensions-model</module>'
            '</modules></project>'
        )
        for module in ("library", "agentscope-extensions/agentscope-extensions-model"):
            directory = self.root / module
            directory.mkdir(parents=True)
            children = (
                "<modules><module>agentscope-extensions-model-e2e-tests</module></modules>"
                if module.endswith("model") else ""
            )
            (directory / "pom.xml").write_text(
                f'<project xmlns="http://maven.apache.org/POM/4.0.0">{children}</project>'
            )
        # Excluded trees need not be read or selected, including their children.
        self.assertEqual(release.release_projects(self.root), [
            ".", "library", "agentscope-extensions/agentscope-extensions-model",
        ])

    def test_cli_outputs_only_after_successful_validation(self):
        output = self.root / "github-output"
        env = dict(os.environ, GITHUB_OUTPUT=str(output))
        self.write_poms("2.1.0-SNAPSHOT")
        failed = subprocess.run(
            [sys.executable, str(SCRIPT), "v2.1.0-BETA2"],
            cwd=self.root, env=env, capture_output=True, text=True,
        )
        self.assertNotEqual(failed.returncode, 0)
        self.assertFalse(output.exists())
        self.write_poms("2.1.0-BETA2")
        passed = subprocess.run(
            [sys.executable, str(SCRIPT), "v2.1.0-BETA2"],
            cwd=self.root, env=env, capture_output=True, text=True,
        )
        self.assertEqual(passed.returncode, 0, passed.stderr)
        self.assertEqual(output.read_text(), "version=2.1.0-BETA2\nprojects=.\n")


if __name__ == "__main__":
    unittest.main()
