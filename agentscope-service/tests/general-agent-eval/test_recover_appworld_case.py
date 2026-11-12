# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import copy
import unittest
from urllib.parse import parse_qs, urlsplit

from recover_appworld_case import paginated, validate_candidate, validate_deadline


class RecoveryGuardTest(unittest.TestCase):
    def setUp(self):
        turn = {'id': 'turn-a', 'session_id': 'session-a', 'status': 'cancelled'}
        self.evidence = {
            'row': {'trial': 'trial-a', 'target': 'codex', 'status': 'budget_exceeded',
                    'session': 'session-a', 'turn': 'turn-a', 'reward': None},
            'turn': turn, 'turns': {'data': [copy.deepcopy(turn)], 'has_more': False},
            'budget': {'usage': {'last_denial': {'limit': 'usage_unavailable',
                                               'session_id': 'session-a', 'turn_id': 'turn-a'},
                                 'model_calls': 2, 'tool_calls': 1, 'in_flight_calls': 1, 'total_tokens': 100}},
            'requests': [
                {'kind': 'model', 'status': 'succeeded', 'finished': 1,
                 'audit': {'stream_completed': True, 'usage': {'total_tokens': 100}}},
                {'kind': 'tool', 'status': 'succeeded', 'finished': 2, 'audit': {}},
                {'kind': 'model', 'status': 'succeeded', 'finished': 3,
                 'audit': {'stream_completed': True, 'usage': {'total_tokens': 120}}}],
            'events': [{'type': 'span.model_request_start'}, {'type': 'span.model_request_end'},
                       {'type': 'span.model_request_start'}]}

    def test_confirmed_accounting_failure_preserves_observed_usage(self):
        result = validate_candidate(**self.evidence)
        self.assertEqual(result['observed_provider_tokens'], 220)
        self.assertEqual(self.evidence['row']['reward'], None)
        self.assertEqual(self.evidence['budget']['usage']['total_tokens'], 100)

    def test_running_or_unlisted_turn_requires_manual_inspection(self):
        for change in ['running', 'has_more', 'different_session', 'missing_turn']:
            with self.subTest(change=change):
                evidence = copy.deepcopy(self.evidence)
                if change == 'running':
                    evidence['turns']['data'].append({'session_id': 'session-a', 'status': 'running'})
                elif change == 'has_more':
                    evidence['turns']['has_more'] = True
                elif change == 'different_session':
                    evidence['turn']['session_id'] = 'other'
                else:
                    evidence['turns']['data'][0]['id'] = 'other'
                with self.assertRaises(ValueError):
                    validate_candidate(**evidence)

    def test_pending_failed_or_ambiguous_tool_prevents_cleanup(self):
        for status, finished in [('pending', None), ('succeeded', None), ('failed', 2)]:
            with self.subTest(status=status, finished=finished):
                evidence = copy.deepcopy(self.evidence)
                evidence['requests'][1].update(status=status, finished=finished)
                with self.assertRaises(ValueError):
                    validate_candidate(**evidence)

    def test_unknown_provider_usage_and_partial_stream_are_not_this_failure(self):
        for update in [{'usage': None}, {'usage': {}}, {'stream_completed': False}]:
            with self.subTest(update=update):
                evidence = copy.deepcopy(self.evidence)
                evidence['requests'][-1]['audit'].update(update)
                with self.assertRaises(ValueError):
                    validate_candidate(**evidence)

    def test_other_denials_and_accounting_differences_fail_closed(self):
        for change in ['numeric_limit', 'different_turn', 'multiple_missing', 'events', 'scored']:
            with self.subTest(change=change):
                evidence = copy.deepcopy(self.evidence)
                if change == 'numeric_limit':
                    evidence['budget']['usage']['last_denial']['limit'] = 'max_total_tokens'
                elif change == 'different_turn':
                    evidence['budget']['usage']['last_denial']['turn_id'] = 'other'
                elif change == 'multiple_missing':
                    evidence['budget']['usage']['total_tokens'] = 0
                elif change == 'events':
                    evidence['events'].append({'type': 'span.model_request_end'})
                else:
                    evidence['row']['reward'] = 0
                with self.assertRaises(ValueError):
                    validate_candidate(**evidence)

    def test_repeated_pagination_cursor_fails_instead_of_partial_export(self):
        class Client:
            def api(self, _):
                return {'data': [], 'has_more': True, 'next_cursor': 'same'}
        with self.assertRaises(ValueError):
            paginated(Client(), '/events')

    def test_history_signed_page_cursor_exports_beyond_first_hundred(self):
        calls = []
        class Client:
            def api(self, path):
                calls.append(parse_qs(urlsplit(path).query))
                if len(calls) == 1:
                    return {'data': list(range(100)), 'next_page': 'signed+/cursor='}
                return {'data': list(range(100, 157)), 'next_page': None}
        pages = paginated(Client(), '/events')
        self.assertEqual(sum(len(page['data']) for page in pages), 157)
        self.assertEqual(calls[1], {'limit': ['100'], 'page': ['signed+/cursor=']})

    def test_output_uses_after_cursor(self):
        calls = []
        class Client:
            def api(self, path):
                calls.append(parse_qs(urlsplit(path).query))
                if len(calls) == 1:
                    return {'data': [1], 'has_more': True, 'next_cursor': 'output-cursor'}
                return {'data': [2], 'has_more': False, 'next_cursor': None}
        self.assertEqual(len(paginated(Client(), '/turns/turn/output')), 2)
        self.assertEqual(calls[1]['after'], ['output-cursor'])

    def test_repeated_history_cursor_and_conflicting_output_metadata_fail(self):
        for page in [{'data': [], 'next_page': 'same'},
                     {'data': [], 'has_more': False, 'next_cursor': 'unexpected'}]:
            class Client:
                def api(self, _): return page
            with self.assertRaises(ValueError): paginated(Client(), '/events')


class DeadlineRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.evidence = {
            'row': {'target': 'agentscope', 'session': 'session', 'turn': 'turn', 'status': 'budget_exceeded',
                    'reward': None, 'started': 1000, 'elapsed_seconds': 902,
                    'budget': {'deadline_epoch_ms': 1900000},
                    'error': 'CaseBudgetExceeded: Turn turn exceeded the evaluation wait budget'},
            'turn': {'id': 'turn', 'session_id': 'session', 'status': 'cancelled'},
            'turns': {'has_more': False, 'data': [{'id': 'turn', 'session_id': 'session', 'status': 'cancelled'}]},
            'budget': {'usage': {'model_calls': 2, 'tool_calls': 1, 'in_flight_calls': 0,
                                 'total_tokens': 100, 'unreported_calls': 1}},
            'requests': [
                {'kind': 'model', 'status': 'succeeded', 'finished': 1001,
                 'audit': {'stream_completed': True, 'usage': {'total_tokens': 100}}},
                {'kind': 'tool', 'status': 'succeeded', 'finished': 1002, 'audit': {}},
                {'kind': 'model', 'status': 'failed', 'finished': 1901,
                 'audit': {'error_type': 'BrokenPipeError', 'usage_unknown': True, 'stream_completed': False}}],
            'events': [{'type': 'managed_agents.turn.cancelled'}]}

    def test_settled_deadline_preserves_unknown_last_usage(self):
        result = validate_deadline(**self.evidence)
        self.assertEqual(result['observed_provider_tokens'], 100)
        self.assertEqual(result['unknown_provider_requests'], 1)
        self.assertTrue(result['observed_tokens_are_lower_bound'])
        self.assertNotIn('usage', self.evidence['requests'][-1]['audit'])

    def test_nonterminal_pending_tool_early_failure_and_other_signature_block(self):
        for mutation in ['running', 'pending', 'tool_failed', 'early', 'other_error',
                         'model_error', 'wrong_tokens', 'no_cancel', 'paged_turns']:
            evidence = copy.deepcopy(self.evidence)
            if mutation == 'running': evidence['turn']['status'] = 'running'
            elif mutation == 'pending': evidence['requests'][-1]['status'] = 'pending'
            elif mutation == 'tool_failed': evidence['requests'][1]['status'] = 'failed'
            elif mutation == 'early': evidence['row']['elapsed_seconds'] = 800
            elif mutation == 'other_error': evidence['row']['error'] = 'provider error'
            elif mutation == 'model_error': evidence['requests'][-1]['audit']['error_type'] = 'TimeoutError'
            elif mutation == 'wrong_tokens': evidence['budget']['usage']['total_tokens'] = 0
            elif mutation == 'no_cancel': evidence['events'] = []
            else: evidence['turns']['has_more'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_deadline(**evidence)


if __name__ == '__main__':
    unittest.main()
