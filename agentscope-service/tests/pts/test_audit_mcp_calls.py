# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest
from audit_mcp_calls import audit


class AuditTest(unittest.TestCase):
    def check(self, events=None, diagnostics=None, call='c'):
        events = events if events is not None else [
            {'event': e, 'marker': 'm', 'call_id': 'c', 'pod': 'p'}
            for e in ('long_wait.started', 'long_wait.completed')]
        diagnostics = diagnostics if diagnostics is not None else [
            {'pod': 'p', 'value': {'trace_dropped': 0, 'trace_pending': 0, 'active_executions': 0}}]
        return audit('b', [{'marker': 'm'}], [{'marker': 'm', 'physical': [
            {'call_id': call, 'state': 'succeeded'}]}], events, diagnostics)

    def test_independent_logs_and_captured_result_must_agree(self):
        self.assertTrue(self.check()['passed'])
        self.assertFalse(self.check(call='different')['passed'])
        self.assertFalse(self.check(events=[])['passed'])

    def test_duplicate_physical_execution_fails_even_if_platform_captured_once(self):
        events = [{'event': e, 'marker': 'm', 'call_id': c, 'pod': 'p'}
                  for c in ('c', 'duplicate') for e in ('long_wait.started', 'long_wait.completed')]
        self.assertFalse(self.check(events=events)['passed'])

    def test_missing_or_dropped_log_evidence_cannot_certify_execution(self):
        self.assertFalse(self.check(diagnostics=[])['passed'])
        for field in ('trace_dropped', 'trace_pending', 'active_executions'):
            value = {'trace_dropped': 0, 'trace_pending': 0, 'active_executions': 0, field: 1}
            self.assertFalse(self.check(diagnostics=[{'pod': 'p', 'value': value}])['passed'])
