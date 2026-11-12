# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
from pathlib import Path
import importlib
import sys
import tempfile
import time
import unittest
from types import ModuleType
from unittest.mock import patch

from appworld_process import AppWorldProcess, task_evaluation_imports


class SimulatedWorld:
    def __init__(self, root, task, trial):
        self.root, self.task = Path(root), task
        time.time = lambda: 123.0

    def initial(self):
        return {'simulated_time': time.time()}

    def evaluate(self):
        if self.task == 'module_collision':
            import evaluation
            sys.path.append(str(self.root))
            try:
                with task_evaluation_imports():
                    task = importlib.import_module('evaluation')
                    importlib.reload(task)
                    value = task.evaluate()
                return {'value': value, 'runner_restored': sys.modules['evaluation'] is evaluation}
            finally:
                sys.path.pop()
        if self.task == 'error':
            raise ValueError('official evaluator failed')
        if self.task == 'timeout':
            time.sleep(10)
        return {'success': True, 'simulated_time': time.time()}

    def close(self):
        (self.root / 'closed').write_text('closed')


class AppWorldProcessTest(unittest.TestCase):
    def test_configmap_file_symlinks_do_not_leave_runner_directory_on_import_path(self):
        with tempfile.TemporaryDirectory() as root:
            code, revision, task = (Path(root, name) for name in ['code', 'revision', 'task'])
            for directory in [code, revision, task]:
                directory.mkdir()
            for name in ['evaluation.py', 'appworld_process.py']:
                (revision / name).write_text('# runner module\n')
                (code / name).symlink_to(revision / name)
            (task / 'evaluation.py').write_text('def evaluate(): return 42\n')
            runner = ModuleType('evaluation')
            runner.__file__ = str(code / 'evaluation.py')
            with patch.dict(sys.modules, {'evaluation': runner}), \
                    patch('appworld_process.__file__', str(code / 'appworld_process.py')), \
                    patch.object(sys, 'path', [str(code), str(task)]):
                with task_evaluation_imports():
                    self.assertEqual(importlib.import_module('evaluation').evaluate(), 42)
                self.assertIs(sys.modules['evaluation'], runner)
                self.assertEqual(sys.path, [str(code), str(task)])

    def test_official_task_module_wins_over_runner_module_without_changing_evaluator(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'evaluation.py').write_text('def evaluate(): return 42\n')
            world = AppWorldProcess(root, 'module_collision', 'trial', factory=SimulatedWorld)
            try:
                self.assertEqual(world.evaluate(), {'value': 42, 'runner_restored': True})
            finally:
                world.stop()

    def test_simulated_clock_cannot_change_runner_deadlines_or_elapsed_time(self):
        with tempfile.TemporaryDirectory() as root:
            started, monotonic = time.time(), time.monotonic()
            world = AppWorldProcess(root, 'ok', 'trial', factory=SimulatedWorld)
            try:
                self.assertEqual(world.initial['simulated_time'], 123)
                self.assertGreaterEqual(time.time(), started)
                self.assertGreaterEqual(time.monotonic(), monotonic)
                self.assertEqual(world.evaluate(), {'success': True, 'simulated_time': 123})
                world.close()
                self.assertTrue(Path(root, 'closed').exists())
                self.assertFalse(world.process.is_alive())
            finally:
                world.stop()

    def test_failed_evaluation_preserves_remote_state_and_stops_controller(self):
        with tempfile.TemporaryDirectory() as root:
            world = AppWorldProcess(root, 'error', 'trial', factory=SimulatedWorld)
            try:
                with self.assertRaisesRegex(RuntimeError, 'official evaluator failed'):
                    world.evaluate()
            finally:
                world.stop()
            self.assertFalse(Path(root, 'closed').exists())
            self.assertFalse(world.process.is_alive())

    def test_control_timeout_uses_real_clock_and_does_not_reset_world(self):
        with tempfile.TemporaryDirectory() as root:
            world = AppWorldProcess(root, 'timeout', 'trial', factory=SimulatedWorld)
            world.timeout = .1
            started = time.monotonic()
            try:
                with self.assertRaises(TimeoutError):
                    world.evaluate()
            finally:
                world.stop()
            self.assertLess(time.monotonic() - started, 3)
            self.assertFalse(Path(root, 'closed').exists())
            self.assertFalse(world.process.is_alive())
