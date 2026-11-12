# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Bounded best-effort stdout records; slow log collection never blocks tool execution."""
import json
import queue
import threading


class TraceSink:
    def __init__(self, capacity=65536, writer=None):
        self.queue = queue.Queue(capacity)
        self.writer = writer or (lambda record: print(json.dumps(record), flush=True))
        self.dropped = 0
        self.thread = None
        self.stopped = threading.Event()

    def emit(self, record):
        try:
            self.queue.put_nowait(record)
        except queue.Full:
            self.dropped += 1

    def pending(self):
        return self.queue.qsize()

    def start(self):
        self.stopped.clear()
        self.thread = threading.Thread(target=self._run, daemon=True, name='mcp-trace')
        self.thread.start()

    def _run(self):
        while not self.stopped.is_set() or not self.queue.empty():
            try:
                record = self.queue.get(timeout=.1)
            except queue.Empty:
                continue
            try:
                self.writer(record)
            except Exception:
                self.dropped += 1

    def close(self):
        self.stopped.set()
        if self.thread:
            self.thread.join(timeout=5)
