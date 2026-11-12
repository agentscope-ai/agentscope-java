# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Transport regression tests, without model calls or changes to active batches."""
import json
import unittest
from unittest.mock import patch

from provider_stream import stream_model


class Socket:
    def settimeout(self, timeout):
        assert timeout > 0


class Response:
    status = 200

    def __init__(self, chunks):
        self.chunks = iter(chunks)
        self.reads = 0

    def getheader(self, *_):
        return 'text/event-stream'

    def read1(self, _):
        self.reads += 1
        value = next(self.chunks)
        if isinstance(value, Exception):
            raise value
        return value


class Connection:
    def __init__(self, response):
        self.response = response
        self.sock = Socket()
        self.closed = False

    def request(self, *_):
        pass

    def getresponse(self):
        return self.response

    def close(self):
        self.closed = True


class StreamTest(unittest.TestCase):
    def terminal(self, kind='response.completed'):
        return b'data: ' + json.dumps({'type': kind, 'response': {
            'model': 'fixture', 'status': kind.removeprefix('response.'),
            'usage': {'input_tokens': 3, 'output_tokens': 7, 'total_tokens': 10},
            'incomplete_details': {'reason': 'max_output_tokens'} if kind.endswith('incomplete') else None,
        }}).encode() + b'\n\n'

    def run_stream(self, chunks, emit=None):
        response = Response(chunks)
        connection = Connection(response)
        self.audit, self.forwarded = {}, []
        with patch('provider_stream.http.client.HTTPConnection', return_value=connection):
            try:
                status = stream_model('http://fixture/responses', b'{}', {}, 180,
                                      lambda *_: None, emit or self.forwarded.append, self.audit)
                return status, response
            finally:
                self.assertTrue(connection.closed)

    def test_completed_does_not_wait_for_tcp_eof(self):
        event = self.terminal()
        status, response = self.run_stream([event, TimeoutError('upstream did not close')])
        self.assertEqual(status, 'succeeded')
        self.assertEqual(response.reads, 1)
        self.assertEqual(b''.join(self.forwarded), event)
        self.assertEqual(self.audit['usage']['total_tokens'], 10)
        self.assertTrue(self.audit['stream_terminal_received'])

    def test_incomplete_and_failed_keep_usage_without_claiming_success(self):
        for kind in ['response.incomplete', 'response.failed']:
            with self.subTest(kind=kind):
                status, response = self.run_stream([self.terminal(kind), TimeoutError()])
                self.assertEqual(status, 'provider_error')
                self.assertEqual(response.reads, 1)
                self.assertFalse(self.audit['stream_completed'])
                self.assertTrue(self.audit['stream_terminal_received'])
                self.assertEqual(self.audit['terminal_event'], kind)
                self.assertEqual(self.audit['usage']['total_tokens'], 10)

    def test_waits_for_split_sse_delimiter_and_forwards_it(self):
        event = self.terminal()
        status, response = self.run_stream([event[:-1], event[-1:], TimeoutError()])
        self.assertEqual(status, 'succeeded')
        self.assertEqual(response.reads, 2)
        self.assertEqual(b''.join(self.forwarded), event)

    def test_nonterminal_timeout_retains_unknown_usage(self):
        with self.assertRaises(TimeoutError):
            self.run_stream([b'data: {"type":"response.created"}\n\n', TimeoutError()])
        self.assertNotIn('usage', self.audit)
        self.assertTrue(self.audit['usage_unknown'])
        self.assertEqual(self.audit['failure_stage'], 'upstream_read')
        self.assertFalse(self.audit['stream_terminal_received'])

    def test_truncated_terminal_event_does_not_claim_complete_stream(self):
        status, _ = self.run_stream([self.terminal()[:-1], b''])
        self.assertEqual(status, 'provider_error')
        self.assertFalse(self.audit['stream_completed'])

    def test_downstream_failure_does_not_erase_received_usage(self):
        def broken(_):
            raise BrokenPipeError()
        with self.assertRaises(BrokenPipeError):
            self.run_stream([self.terminal()], emit=broken)
        self.assertEqual(self.audit['failure_stage'], 'downstream_write')
        self.assertFalse(self.audit['usage_unknown'])
        self.assertEqual(self.audit['usage']['total_tokens'], 10)


if __name__ == '__main__':
    unittest.main()
