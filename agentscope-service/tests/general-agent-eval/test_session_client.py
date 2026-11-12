# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest
from urllib.error import HTTPError
from unittest.mock import Mock

from session_client import SessionClient


class SessionClientTest(unittest.TestCase):
    def test_reads_every_output_page(self):
        client = SessionClient("http://unused", {})
        client.api = Mock(side_effect=[
            {"output_available": True, "data": [{"id": "a"}], "has_more": True, "next_cursor": "a/b"},
            {"output_available": True, "data": [{"id": "b"}], "has_more": False}])
        self.assertEqual(client.output("/turn"), [{"id": "a"}, {"id": "b"}])
        self.assertIn("after=a%2Fb", client.api.call_args.args[0])

    def test_repeated_cursor_is_not_silently_accepted(self):
        client = SessionClient("http://unused", {})
        client.api = Mock(return_value={"output_available": True, "data": [],
            "has_more": True, "next_cursor": "same"})
        with self.assertRaises(ValueError):
            client.output("/turn")

    def test_cancel_targets_explicit_turn_extension(self):
        client = SessionClient("http://unused", {})
        client.api = Mock()
        client.cancel("session", "turn")
        event = client.api.call_args.args[1]["events"][0]
        self.assertEqual(event, {"type": "user.interrupt",
            "managed_agents": {"intent": "cancel", "turn_id": "turn"}})

    def test_only_assistant_text_is_returned_to_user_simulator(self):
        self.assertEqual(SessionClient.text([
            {"type": "user.message", "content": [{"type": "text", "text": "private"}]},
            {"type": "agent.message", "content": [{"type": "text", "text": "reply"}]}]), "reply")

    def test_cancel_conflict_requires_exact_turn_terminal_confirmation(self):
        for status in ['failed', 'cancelled', 'completed', 'running']:
            client = SessionClient('http://unused', {})
            conflict = HTTPError('http://unused', 409, 'Conflict', {}, None)
            client.api = Mock(side_effect=[conflict, {'id': 'turn', 'status': status}])
            if status == 'running':
                with self.assertRaises(HTTPError):
                    client.cancel('session', 'turn')
            else:
                receipt = client.cancel('session', 'turn')
                self.assertTrue(receipt['already_terminal'])
                self.assertEqual(receipt['state']['status'], status)
            self.assertEqual(client.api.call_args.args, ('/v1/sessions/session/turns/turn',))

    def test_cancel_transport_failures_are_not_assumed_terminal(self):
        client = SessionClient('http://unused', {})
        client.api = Mock(side_effect=HTTPError('http://unused', 503, 'Unavailable', {}, None))
        with self.assertRaises(HTTPError):
            client.cancel('session', 'turn')
        self.assertEqual(client.api.call_count, 1)


if __name__ == "__main__":
    unittest.main()
