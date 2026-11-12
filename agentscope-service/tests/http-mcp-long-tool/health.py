# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Process-local counters and event-loop lag; no external health dependency."""
import asyncio
import os
import time


class HealthMonitor:
    def __init__(self, active, counters, trace, interval=2, clock=time.monotonic):
        self.active, self.counters, self.trace = active, counters, trace
        self.interval, self.clock = interval, clock
        self.loop_lag_ms = self.max_loop_lag_ms = 0
        self.task = None
        self.stopping = False

    def snapshot(self):
        return {'mode': 'performance', 'durable_recovery': False,
                'ready': self.task is not None and not self.stopping,
                'event_loop_lag_ms': self.loop_lag_ms, 'max_event_loop_lag_ms': self.max_loop_lag_ms,
                'active_executions': len(self.active), **self.counters,
                'trace_dropped': self.trace.dropped, 'trace_pending': self.trace.pending()}

    async def run(self):
        while True:
            expected = self.clock() + self.interval
            await asyncio.sleep(self.interval)
            self.loop_lag_ms = max(0, (self.clock() - expected) * 1000)
            self.max_loop_lag_ms = max(self.max_loop_lag_ms, self.loop_lag_ms)
            self.trace.emit({'event': 'mcp.fixture.health', 'at': time.time(),
                             'pod': os.environ.get('HOSTNAME', 'local'), **self.snapshot()})

    def start(self):
        self.stopping = False
        self.task = asyncio.create_task(self.run())

    async def close(self):
        self.stopping = True
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
