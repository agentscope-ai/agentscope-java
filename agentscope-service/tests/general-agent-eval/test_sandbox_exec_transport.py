# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown, read_complete_stream


class Stream:
    def __init__(self, code, chunks=()):
        self.returncode, self.chunks, self.closed = code, list(chunks), False
        self.frame = None

    def is_open(self):
        return bool(self.chunks)

    def update(self, timeout):
        self.frame = self.chunks.pop(0)

    def peek_stdout(self):
        return self.frame is not None

    def read_stdout(self):
        value, self.frame = self.frame, None
        return value

    def peek_stderr(self):
        return False

    def close(self):
        self.closed = True


class ExecutionTransportTests(unittest.TestCase):
    def test_stderr_peek_can_receive_late_stdout_and_close_the_socket(self):
        # Kubernetes WSClient.peek_channel calls update itself. Inspecting stderr
        # can therefore receive another stdout frame after stdout was drained.
        class LateStream:
            returncode = 0
            opened = True
            stdout = ''
            def is_open(self): return self.opened
            def update(self, timeout): self.stdout = 'first '
            def peek_stdout(self): return bool(self.stdout)
            def read_stdout(self):
                value, self.stdout = self.stdout, ''
                return value
            def peek_stderr(self):
                if self.opened:
                    self.stdout = 'last'
                    self.opened = False
                return False
            def close(self): self.opened = False
        self.assertEqual(read_complete_stream(LateStream(), 3), ('first last', '', 0))
        with self.assertRaises(ExecutionOutcomeUnknown):
            read_complete_stream(LateStream(), 3, max_output_bytes=7)

    def test_broken_stream_is_unknown_even_if_output_looks_successful(self):
        stream = Stream(None, ['all tests passed\n'])
        with self.assertRaises(ExecutionOutcomeUnknown):
            read_complete_stream(stream, 3)
        self.assertTrue(stream.closed)

    def test_real_nonzero_exit_is_preserved(self):
        self.assertEqual(read_complete_stream(Stream(7, ['failure']), 3), ('failure', '', 7))

    def test_split_utf8_and_text_frames_preserve_output(self):
        raw = '中文'.encode()
        self.assertEqual(read_complete_stream(Stream(0, [raw[:2], raw[2:], ' done']), 3),
                         ('中文 done', '', 0))

    def test_output_limit_does_not_invent_exit_status(self):
        stream = Stream(0, ['too large'])
        with self.assertRaises(ExecutionOutcomeUnknown):
            read_complete_stream(stream, 3, max_output_bytes=2)
        self.assertTrue(stream.closed)

    def test_deadline_remains_unknown_and_closes_connection(self):
        stream = Stream(None)
        with self.assertRaises(ExecutionOutcomeUnknown):
            read_complete_stream(stream, 0)
        self.assertTrue(stream.closed)


if __name__ == '__main__':
    unittest.main()
