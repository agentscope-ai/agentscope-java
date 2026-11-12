# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Session transport for benchmark drivers; tools execute on the server."""
from datetime import datetime
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from evaluation import CaseBudgetExceeded


class SessionClient:
    def __init__(self, base_url, headers):
        self.base = base_url.rstrip("/")
        self.turn_timings = {}
        self.headers = {**headers, "Content-Type": "application/json",
                        "Managed-Agents-Extensions": "turn-inputs-v1,session-budgets-v1"}

    def api(self, path, body=None, key=None, method=None):
        headers = dict(self.headers)
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.base + path,
            None if body is None else json.dumps(body).encode(), headers, method=method)
        # No automatic mutation retries: the caller persists admission identities.
        with urllib.request.urlopen(request, timeout=45) as response:
            return json.load(response)

    def create(self, agent, environment, metadata, key):
        return self.api("/v1/sessions", {"agent": agent, "environment_id": environment,
            "title": "General agent evaluation", "metadata": metadata}, key)

    def send(self, session, text, key):
        started = time.time()
        result = self.api(f"/v1/sessions/{session}/events", {"events": [{
            "type": "user.message", "content": [{"type": "text", "text": text}],
            "managed_agents": {"intent": "new_turn"}}]}, key)
        turn = result["data"][0]["managed_agents"]["receipt"]["id"]
        self.turn_timings[turn] = {'admission_started': started, 'admission_returned': time.time()}
        return turn

    def cancel(self, session, turn):
        try:
            return self.api(f"/v1/sessions/{session}/events", {"events": [{
                "type": "user.interrupt", "managed_agents": {
                    "intent": "cancel", "turn_id": turn}}]}, str(uuid.uuid4()))
        except urllib.error.HTTPError as error:
            if error.code != 409:
                raise
            # The budget monitor and the runtime may terminate a Turn concurrently.
            # A conflict is safe only after reading this exact Turn's terminal state.
            state = self.api(f"/v1/sessions/{session}/turns/{turn}")
            if state.get('status') not in {'failed', 'cancelled', 'completed'}:
                raise
            return {'already_terminal': True, 'state': state, 'cancel_http_status': 409}

    def wait(self, session, turn, timeout=300, monitor_budget=False):
        path = f"/v1/sessions/{session}/turns/{turn}"
        deadline = time.monotonic() + timeout
        state = None
        timing = self.turn_timings.setdefault(turn, {})
        while time.monotonic() < deadline:
            if monitor_budget:
                budget = self.api(f'/v1/sessions/{session}/budget')
                denial = budget.get('usage', {}).get('last_denial')
                if denial and denial.get('code') == 'budget_exceeded':
                    raise CaseBudgetExceeded('Platform budget denied execution: ' + denial['limit'])
            state = self.api(path)
            for field in ['created_at', 'started_at', 'completed_at']:
                value = state.get(field)
                if isinstance(value, str):
                    timing['server_' + field] = datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
            if all('server_' + f in timing for f in ['created_at', 'started_at']):
                timing['server_admission_to_start_seconds'] = timing['server_started_at'] - timing['server_created_at']
            if all('server_' + f in timing for f in ['started_at', 'completed_at']):
                timing['server_start_to_terminal_seconds'] = timing['server_completed_at'] - timing['server_started_at']
            if state['status'] in {'running', 'waiting', 'in_progress'}:
                timing.setdefault('first_active_observed', time.time())
            if state['status'] in {'failed', 'cancelled', 'completed'}:
                timing.setdefault('terminal_observed', time.time())
            if state["status"] in {"failed", "cancelled"}:
                return state, []
            if state["status"] == "completed":
                try:
                    rows = self.output(path)
                    if rows is not None:
                        timing['output_available_observed'] = time.time()
                        return state, rows
                except urllib.error.HTTPError as error:
                    if error.code != 503:
                        raise
            time.sleep(2)
        if state and state['status'] == 'completed':
            raise TimeoutError(f"Turn {turn} completed but its output projection is unavailable")
        # The Runner persists this failure, requests cancellation, and retains the environment lease.
        raise CaseBudgetExceeded(f"Turn {turn} exceeded the evaluation wait budget")

    def output(self, turn_path):
        rows, cursor, seen = [], None, set()
        while True:
            query = "?limit=100" + ("&after=" + urllib.parse.quote(cursor, safe="") if cursor else "")
            page = self.api(turn_path + "/output" + query)
            if not page.get("output_available"):
                return None
            rows.extend(page.get("data", []))
            if not page.get("has_more"):
                return rows
            cursor = page.get("next_cursor")
            if not cursor or cursor in seen:
                raise ValueError("Output pagination did not advance")
            seen.add(cursor)

    @staticmethod
    def text(events):
        return "\n".join(block["text"] for event in events
                         if event.get("type") == "agent.message"
                         for block in event.get("content", []) if block.get("type") == "text")
