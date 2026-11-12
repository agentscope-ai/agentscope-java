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

import copy
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest import mock

import yaml


spec = importlib.util.spec_from_file_location(
    "sync_helm", Path(__file__).resolve().parents[1] / "sync_helm.py"
)
sync_helm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync_helm)


class HelmSyncTest(unittest.TestCase):
    def setUp(self):
        for key, value in {"UPSTREAM": "example/managed-agents", "HELM_REPOSITORY": "example/helm-charts", "HELM_URL": "https://helm.example.com"}.items():
            patcher = mock.patch.object(sync_helm, key, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.version = "2.1.0-BETA2"
        self.archive = b"original Release chart"
        self.digest = hashlib.sha256(self.archive).hexdigest()
        self.release = {
            "tag_name": "v" + self.version, "draft": False,
            "assets": [{"name": f"managed-agents-{self.version}.tgz",
                        "digest": "sha256:" + self.digest}],
        }
        self.entry = {
            "version": self.version, "appVersion": self.version, "digest": self.digest,
            "urls": [f"{sync_helm.HELM_URL}/managed-agents-{self.version}.tgz"],
        }

    def test_public_chart_matches_release(self):
        self.assertEqual(sync_helm.release_digest(self.release, self.version), self.digest)
        index = {"entries": {"managed-agents": [self.entry]}}
        sync_helm.verify_publication(index, self.archive, self.version, self.digest)

    def test_reject_draft_wrong_tag_missing_chart_and_missing_digest(self):
        for change in ({"draft": True}, {"tag_name": "v2.1.0-BETA1"}, {"assets": []},
                       {"assets": [{"name": f"managed-agents-{self.version}.tgz"}]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                sync_helm.release_digest(dict(self.release, **change), self.version)

    def test_reject_index_mismatches_and_changed_chart(self):
        for change in ({"version": "2.1.0-BETA1"}, {"appVersion": "2.1.0-BETA1"},
                       {"digest": "0" * 64}, {"urls": ["https://example.com/changed.tgz"]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                entry = dict(self.entry, **change)
                sync_helm.verify_publication({"entries": {"managed-agents": [entry]}},
                                             self.archive, self.version, self.digest)
        with self.assertRaises(ValueError):
            sync_helm.verify_publication({"entries": {"managed-agents": [self.entry]}},
                                         b"different bytes", self.version, self.digest)

    def test_reject_duplicate_chart_versions(self):
        index = {"entries": {"managed-agents": [self.entry, copy.deepcopy(self.entry)]}}
        with self.assertRaises(ValueError):
            sync_helm.verify_publication(index, self.archive, self.version, self.digest)

    def test_invalid_version_does_not_dispatch(self):
        with mock.patch.object(sync_helm, "gh_api") as api:
            for version in ("v2.1.0-BETA2", "2.1.0-SNAPSHOT", "2.1.0;echo test"):
                with self.subTest(version=version), self.assertRaises(ValueError):
                    sync_helm.sync(version)
            api.assert_not_called()

    def test_failed_downstream_workflow_does_not_report_success(self):
        with mock.patch.object(sync_helm, "gh_api", side_effect=[
            self.release, {"workflow_run_id": 123},
        ]), mock.patch.object(sync_helm.subprocess, "run", side_effect=
                             subprocess.CalledProcessError(1, ["gh", "run", "watch"])), \
                mock.patch.object(sync_helm, "urlopen") as download, \
                mock.patch.dict(sync_helm.os.environ, {"GITHUB_STEP_SUMMARY": ""}):
            with self.assertRaises(subprocess.CalledProcessError):
                sync_helm.sync(self.version)
            download.assert_not_called()

    def test_waits_for_new_public_index_after_pages_deploy(self):
        old_index = {"entries": {"managed-agents": []}}
        new_index = {"entries": {"managed-agents": [self.entry]}}
        responses = [io.BytesIO(yaml.safe_dump(old_index).encode()), io.BytesIO(self.archive),
                     io.BytesIO(yaml.safe_dump(new_index).encode()), io.BytesIO(self.archive)]
        with mock.patch.object(sync_helm, "gh_api", side_effect=[
            self.release, {"workflow_run_id": 123},
        ]) as api, mock.patch.object(sync_helm.subprocess, "run"), \
                mock.patch.object(sync_helm, "urlopen", side_effect=responses), \
                mock.patch.object(sync_helm.time, "sleep") as sleep, \
                mock.patch.dict(sync_helm.os.environ, {"GITHUB_STEP_SUMMARY": ""}):
            sync_helm.sync(self.version)
            api.assert_called_with(
                "repos/example/helm-charts/actions/workflows/publish.yml/dispatches",
                {"ref": "main", "inputs": {"version": self.version}},
            )
            sleep.assert_called_once_with(10)


if __name__ == "__main__":
    unittest.main()

class ExplicitTargetsTest(unittest.TestCase):
    def test_no_upstream_account_is_contacted_without_configuration(self):
        with mock.patch.object(sync_helm, "UPSTREAM", ""), mock.patch.object(sync_helm, "gh_api") as api:
            with self.assertRaisesRegex(ValueError, "Configure MANAGED_AGENTS"):
                sync_helm.sync("2.1.0-BETA1")
            api.assert_not_called()
