# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
import unittest
from unittest.mock import Mock
from health import HealthMonitor
from telemetry import TraceSink


class HealthTest(unittest.IsolatedAsyncioTestCase):
    async def test_health_has_no_external_dependency_and_records_lag(self):
        trace = TraceSink()
        monitor = HealthMonitor(set(), {'started': 7}, trace, interval=.01)
        self.assertFalse(monitor.snapshot()['ready'])
        monitor.start()
        await asyncio.sleep(.025)
        state = monitor.snapshot()
        self.assertTrue(state['ready'])
        self.assertFalse(state['durable_recovery'])
        self.assertEqual(state['started'], 7)
        self.assertGreaterEqual(state['event_loop_lag_ms'], 0)
        await monitor.close()
        self.assertFalse(monitor.snapshot()['ready'])

    async def test_slow_log_sink_drops_without_blocking_tool_loop(self):
        sink = TraceSink(capacity=1)
        sink.emit({'first': 1})
        sink.emit({'second': 2})
        self.assertEqual(sink.pending(), 1)
        self.assertEqual(sink.dropped, 1)
        captured = []
        sink.writer = captured.append
        sink.start()
        await asyncio.to_thread(sink.close)
        self.assertEqual(captured, [{'first': 1}])

    async def test_log_sink_failure_is_observable_without_failing_execution(self):
        sink = TraceSink(writer=Mock(side_effect=OSError('stdout unavailable')))
        sink.emit({'event': 'test'})
        sink.start()
        await asyncio.to_thread(sink.close)
        self.assertEqual(sink.dropped, 1)
