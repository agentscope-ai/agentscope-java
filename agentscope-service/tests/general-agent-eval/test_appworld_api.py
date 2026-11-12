# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
from pathlib import Path
import tempfile
import unittest

from appworld_api import reset_idle_slot
from environment_leases import EnvironmentLeases
from evaluation_gateway import CaseStore


class AppWorldResetTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.leases = EnvironmentLeases(Path(self.root.name, 'leases.sqlite'))
        self.store = CaseStore(Path(self.root.name, 'audit.sqlite'))
        self.resets = []
        self.leases.operate('acquire', 'appworld', 'owner')

    def reset(self, owner='owner', authorization='Bearer control'):
        return reset_idle_slot(self.leases.path, self.store.path, owner, authorization,
                               'control', lambda: self.resets.append('reset'))

    def case(self):
        self.store.start({'trial': 'trial', 'slot': 'appworld', 'target': 'pi', 'owner': 'owner'})

    def test_requires_control_credential_and_exact_live_lease(self):
        with self.assertRaises(PermissionError):
            self.reset(authorization='Bearer tool')
        with self.assertRaises(ValueError):
            self.reset(owner='other')
        self.leases.operate('release', 'appworld', 'owner')
        with self.assertRaises(ValueError):
            self.reset()
        self.assertEqual(self.resets, [])

    def test_active_and_interrupted_cases_block_reset(self):
        self.case()
        with self.assertRaisesRegex(ValueError, 'unfinished'):
            self.reset()
        self.store.interrupt_active()
        with self.assertRaisesRegex(ValueError, 'unfinished'):
            self.reset()
        self.assertEqual(self.resets, [])

    def test_pending_io_blocks_reset_even_if_case_is_closed(self):
        self.case()
        with self.store.db() as db:
            db.execute('UPDATE eval_case SET status="closed"')
            db.execute('INSERT INTO eval_request VALUES (?,?,?,?,?,?,?)',
                       ('request', 'trial', 'tool', 1, None, 'pending', '{}'))
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            self.reset()
        self.assertEqual(self.resets, [])

    def test_completed_idle_slot_resets_once_and_keeps_lease_and_audit(self):
        self.case()
        with self.store.db() as db:
            db.execute('UPDATE eval_case SET status="closed"')
        self.assertEqual(self.reset(), {'slot': 'appworld', 'status': 'reset', 'lease': 'held'})
        self.assertEqual(self.resets, ['reset'])
        self.assertEqual(self.leases.operate('check', 'appworld', 'owner')['status'], 'held')
        self.assertEqual(self.store.owned('trial', 'owner')['status'], 'closed')


if __name__ == '__main__':
    unittest.main()
