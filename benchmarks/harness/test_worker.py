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
import unittest
from concurrent.futures import ThreadPoolExecutor
import os
import sqlite3
import sys
import threading
import time
from types import SimpleNamespace
from unittest.mock import Mock, patch
import urllib.error

from worker import AppWorldTask, IsolatedAppWorldTask, Tau, run_case, summary


class TauDomainTest(unittest.TestCase):
    def test_domain_selection_and_initial_state_snapshot(self):
        for task_id, domain, index in (("0", "retail", 0), ("airline:49", "airline", 49)):
            with self.subTest(task_id=task_id):
                env = Mock(data={"orders": {"one": "original"}}, tools_info=[], wiki="policy")
                env.get_data_hash.return_value = "initial"
                user = SimpleNamespace(completion=Mock())
                original = user.completion
                factory = Mock(return_value=env)
                modules = {"tau_bench.envs": SimpleNamespace(get_env=factory, user=user),
                           "tau_bench.types": SimpleNamespace(Action=Mock(), RESPOND_ACTION_NAME="respond")}
                trace = Mock()
                with patch.dict(sys.modules, modules), patch.dict(os.environ, {"MODEL_ID": "fixture"}):
                    adapter = Tau(task_id, trace)
                    factory.assert_called_once_with(domain, user_strategy="llm", user_model="fixture",
                                                    user_provider="openai", task_split="test", task_index=index)
                    env.data["orders"]["one"] = "changed"
                    self.assertEqual(trace.call_args.args[1]["data"]["orders"]["one"], "original")
                    adapter.close()
                    self.assertIs(user.completion, original)

    def test_unknown_domain_fails_before_environment_or_model_initialization(self):
        for task_id in ("banking:0", "airline:-1", "airline:x"):
            with self.subTest(task_id=task_id), self.assertRaises(ValueError):
                Tau(task_id, Mock())


class FrozenClockEnvironment:
    system = observation = initial_hash = "fixture"
    tools = []
    done = False

    def __init__(self, task_id, trace):
        time.time = lambda: 0

    def tool(self, name, arguments):
        return {"main_thread": threading.current_thread() is threading.main_thread(),
                "time": time.time(), "has_cloud_key": "DASHSCOPE_API_KEY" in os.environ}

    def close(self):
        pass


class AppWorldIsolationTest(unittest.TestCase):
    def score_fixture(self, error):
        class Tracker:
            success = True

            def __enter__(self):
                return self

            def __exit__(self, kind, value, tb):
                self.success = kind is None
                return True  # Model the upstream broad suppression, including DB errors.

            def to_dict(self, stats_only):
                return {"success": self.success}

        original_exit = Tracker.__exit__

        def evaluate(suppress_errors):
            self.assertTrue(suppress_errors)
            tracker = Tracker()
            with tracker:
                if error is not None:
                    raise error
            return tracker

        adapter = AppWorldTask.__new__(AppWorldTask)
        adapter.world = Mock()
        adapter.world.evaluate.side_effect = evaluate
        adapter.trace = Mock()
        with patch.dict(sys.modules, {"appworld.evaluator": SimpleNamespace(TestTracker=Tracker)}):
            result = adapter.score()
        self.assertIs(Tracker.__exit__, original_exit)
        adapter.trace.assert_called_once_with("official_reward", result[1])
        return result

    def test_unmet_official_requirements_produce_valid_zero(self):
        score, raw, reason = self.score_fixture(AssertionError("goal unmet"))
        self.assertEqual(score, 0)
        self.assertFalse(raw["success"])
        self.assertIsNone(reason)

    def test_database_exception_is_unscored_with_original_evidence(self):
        score, raw, reason = self.score_fixture(sqlite3.OperationalError("no such table: files"))
        self.assertIsNone(score)
        self.assertEqual(reason, "evaluator_exception")
        self.assertEqual(raw["error_type"], "OperationalError")
        self.assertIn("no such table: files", raw["traceback"])

    def test_successful_official_checks_still_pass(self):
        score, raw, reason = self.score_fixture(None)
        self.assertEqual(score, 1)
        self.assertTrue(raw["success"])
        self.assertIsNone(reason)

    def test_completion_contract_is_in_initial_model_context(self):
        world = Mock()
        world.task.instruction = "Perform the requested action"
        world.task.supervisor = {}
        world.task.datetime = "2023-05-18 12:00:00"
        with patch.dict(sys.modules, {"appworld": SimpleNamespace(AppWorld=Mock(return_value=world))}):
            adapter = AppWorldTask("fixture", Mock())
        self.assertIn("only request actions, omit answer", adapter.system)
        self.assertIn("answer only if the task asks a question", adapter.system)
        self.assertIn("without explanation", adapter.system)
        self.assertIn("Simulated world date and time: 2023-05-18 12:00:00", adapter.observation)
        world.execute.assert_not_called()

    def test_environment_clock_and_signal_thread_do_not_affect_archive_process(self):
        with patch.dict(os.environ, {"DASHSCOPE_API_KEY": "fixture"}):
            adapter = IsolatedAppWorldTask("fixture", lambda *args: None, FrozenClockEnvironment)
            try:
                with ThreadPoolExecutor(max_workers=1) as pool:
                    result = pool.submit(adapter.tool, "execute_code", {}).result(timeout=10)
                self.assertEqual(result, {"main_thread": True, "time": 0, "has_cloud_key": False})
                self.assertGreater(time.time(), 1)
            finally:
                adapter.close()


class ScoringCoverageTest(unittest.TestCase):
    def test_unscored_and_missing_cases_do_not_become_official_zeroes(self):
        result = summary([{"score": 1}, {"score": 0}, {"score": None}], 4)
        self.assertEqual(result["official_success_on_scored"], 0.5)
        self.assertEqual(result["end_to_end_success"], 0.25)
        self.assertEqual(result["scoring_coverage"], 0.5)
        self.assertEqual(result["missing"], 1)

    def test_no_valid_scores_preserves_unknown(self):
        self.assertIsNone(summary([{"score": None}], 1)["official_success_on_scored"])


class MemoryArchive:
    def __init__(self):
        self.objects = {}
        self.events = []

    def put(self, name, value, exclusive=False):
        if exclusive and name in self.objects:
            raise FileExistsError(name)
        self.objects[name] = value

    def event(self, case_id, event, **fields):
        self.events.append(event)


class CaseLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.archive = MemoryArchive()
        self.case = {"dataset": "tau", "task_id": "0", "repeat": 0}
        self.manifest = {"batch_id": "test", "candidate_sha": "candidate",
                         "datasets": {"tau": {"revision": "dataset"}},
                         "max_turns": 2, "case_timeout_seconds": 10}

    def test_official_failure_is_a_valid_zero_and_environment_is_closed(self):
        with patch("worker.Tau") as factory, patch("worker.java") as java:
            adapter = factory.return_value
            adapter.initial_hash = "initial"
            adapter.tools = []
            adapter.done = True
            adapter.score.return_value = (0, {"reward": 0}, None)
            java.side_effect = [{"candidate_sha": "candidate"}, {"text": "done"}, {}]
            result = run_case(self.case, self.archive, self.manifest)
            self.assertEqual(result["status"], "completed")
            self.assertEqual(result["score"], 0)
            adapter.close.assert_called_once()
            self.assertIn("cases/tau-0-r0.json", self.archive.objects)

    def test_uncertain_http_failure_never_scores_or_resets_live_environment(self):
        with patch("worker.Tau") as factory, patch("worker.java") as java:
            adapter = factory.return_value
            adapter.initial_hash = "initial"
            adapter.tools = []
            java.side_effect = [{"candidate_sha": "candidate"}, urllib.error.URLError("timeout")]
            result = run_case(self.case, self.archive, self.manifest)
            self.assertIsNone(result["score"])
            self.assertEqual(result["execution_state"], "unknown")
            adapter.score.assert_not_called()
            adapter.close.assert_not_called()

    def test_duplicate_claim_does_not_start_another_model_call(self):
        self.archive.put("claims/tau-0-r0.json", {})
        with patch("worker.Tau") as factory, patch("worker.java") as java:
            with self.assertRaises(FileExistsError):
                run_case(self.case, self.archive, self.manifest)
            factory.assert_not_called()
            java.assert_not_called()

    def test_wrong_working_tree_snapshot_never_calls_the_model(self):
        self.manifest["candidate_snapshot"] = {"sha256": "expected"}
        with patch("worker.Tau") as factory, patch("worker.java") as java:
            adapter = factory.return_value
            adapter.initial_hash = "initial"
            adapter.tools = []
            java.return_value = {"candidate_sha": "candidate", "candidate_source_sha256": "wrong"}
            result = run_case(self.case, self.archive, self.manifest)
            self.assertIsNone(result["score"])
            self.assertEqual(java.call_count, 1)
            self.assertEqual(java.call_args.args[0], "/start")
            adapter.score.assert_not_called()


if __name__ == "__main__":
    unittest.main()
