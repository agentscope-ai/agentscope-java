# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from export_session_trajectories import export_rows, trajectory


class TrajectoryExportTest(unittest.TestCase):
    def setUp(self):
        self.row = {'dataset': 'appworld', 'target': 'codex', 'trial': 'trial', 'session': 'session', 'turn': 'turn'}
        self.client = Mock()
        self.pages = [
            {'data': [{'id': 'turn', 'session_id': 'session', 'status': 'cancelled'}], 'has_more': False},
            {'data': [{'id': str(i)} for i in range(100)], 'next_page': 'signed'},
            {'data': [{'id': str(i)} for i in range(100, 108)], 'next_page': None},
            {'data': [], 'has_more': False, 'output_available': False}, {'usage': {'in_flight_calls': 1}}]
        self.client.api.side_effect = self.pages

    def test_private_export_includes_all_history_pages_and_is_read_only_on_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            receipts = export_rows(self.client, [self.row], Path(temporary))
            self.assertEqual(receipts[0]['event_count'], 108)
            self.assertEqual(receipts[0]['turn_count'], 1)
            self.client.api.reset_mock()
            self.assertEqual(export_rows(self.client, [self.row], Path(temporary)), receipts)
            self.client.api.assert_not_called()

    def test_running_session_and_missing_recorded_turn_block_export(self):
        for data in [[{'id': 'turn', 'session_id': 'session', 'status': 'running'}], []]:
            self.client.api.side_effect = [{'data': data, 'has_more': False}]
            with self.assertRaises(ValueError): trajectory(self.client, self.row)

    def test_duplicate_events_are_not_accepted_as_complete_history(self):
        self.pages[2]['data'] = [{'id': '0'}]
        with self.assertRaises(ValueError): trajectory(self.client, self.row)

    def test_pre_session_failure_is_explicit_without_creating_a_session(self):
        result = trajectory(self.client, {**self.row, 'session': None, 'turn': None})
        self.assertEqual(result['coverage'], 'no_session_created')
        self.client.api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
