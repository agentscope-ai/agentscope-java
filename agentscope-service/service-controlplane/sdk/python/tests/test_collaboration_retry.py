# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import io
from urllib.error import HTTPError, URLError

import pytest

from agentscope_service.collaboration import CollaborationClient, CollaborationError
import agentscope_service.collaboration as collaboration


class Clock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, duration):
        self.sleeps.append(duration)
        self.now += duration


@pytest.fixture
def clock(monkeypatch):
    value = Clock()
    monkeypatch.setattr(collaboration.time, "monotonic", value.monotonic)
    monkeypatch.setattr(collaboration.time, "sleep", value.sleep)
    return value


def http_error(status):
    return HTTPError("http://control-plane/context", status, "test", {}, io.BytesIO(b'{"error":"test"}'))


@pytest.mark.parametrize("failure", [URLError("restart"), TimeoutError("timeout"), 500, 502, 503, 504])
def test_read_retries_transient_failure_with_remaining_budget(monkeypatch, clock, failure):
    timeouts = []

    def request(req, timeout):
        assert req.method == "GET"
        assert req.get_header("X-agent-task-token") == "task-token"
        timeouts.append(timeout)
        if len(timeouts) == 1:
            clock.now += 0.2
            raise http_error(failure) if isinstance(failure, int) else failure
        return io.BytesIO(b'{"inputs":[]}')

    monkeypatch.setattr(collaboration, "urlopen", request)
    assert CollaborationClient("http://control-plane", timeout=1).task_context("task", "task-token") == {"inputs": []}
    assert timeouts == pytest.approx([1.0, 0.7])
    assert clock.sleeps == [0.1]


def test_read_retry_does_not_reset_timeout_budget(monkeypatch, clock):
    timeouts = []

    def request(req, timeout):
        timeouts.append(timeout)
        clock.now += 0.1
        raise URLError("still unavailable")

    monkeypatch.setattr(collaboration, "urlopen", request)
    with pytest.raises(URLError):
        CollaborationClient("http://control-plane", timeout=0.5).task_context("task", "token")
    assert timeouts == pytest.approx([0.5, 0.3])
    assert sum(clock.sleeps) == pytest.approx(0.3)
    assert clock.now == pytest.approx(0.5)


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409, 429])
def test_business_or_auth_read_errors_do_not_retry(monkeypatch, clock, status):
    attempts = []

    def request(req, timeout):
        attempts.append(req)
        raise http_error(status)

    monkeypatch.setattr(collaboration, "urlopen", request)
    with pytest.raises(CollaborationError) as raised:
        CollaborationClient("http://control-plane").task_context("task", "token")
    assert raised.value.status == status
    assert len(attempts) == 1
    assert clock.sleeps == []


@pytest.mark.parametrize("failure", [URLError("restart"), TimeoutError("timeout"), 500, 502, 503, 504])
def test_mutating_requests_are_never_retried(monkeypatch, clock, failure):
    attempts = []

    def request(req, timeout):
        assert req.method == "POST"
        attempts.append(req)
        raise http_error(failure) if isinstance(failure, int) else failure

    monkeypatch.setattr(collaboration, "urlopen", request)
    with pytest.raises(CollaborationError if isinstance(failure, int) else type(failure)):
        CollaborationClient("http://control-plane").create_child_from_task("task", "token", {"title": "child"})
    assert len(attempts) == 1
    assert clock.sleeps == []


def test_invalid_success_body_does_not_retry(monkeypatch, clock):
    attempts = []
    monkeypatch.setattr(collaboration, "urlopen", lambda *args, **kwargs: attempts.append(1) or io.BytesIO(b'not json'))
    with pytest.raises(ValueError):
        CollaborationClient("http://control-plane").task_context("task", "token")
    assert attempts == [1]
    assert clock.sleeps == []
