"""Public Endpoint/Invocation client. No Issue or runtime Session IDs required."""
from __future__ import annotations

import json
from typing import Any, Iterator
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


class ServiceClient:
    def __init__(self, base_url: str, api_key: str = "", *, api_token: str = "", timeout: float = 30) -> None:
        if bool(api_key) == bool(api_token):
            raise ValueError("provide exactly one endpoint api_key or platform api_token")
        self.base_url, self.api_key, self.timeout = base_url.rstrip("/"), api_key, timeout
        self.api_token = api_token

    def _headers(self) -> dict[str, str]:
        return {"X-API-Key": self.api_key} if self.api_key else {"Authorization": "Bearer " + self.api_token}

    def _request(self, method: str, path: str, body: Any = None, key: str = "") -> Any:
        headers = {**self._headers(), "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        request = Request(self.base_url + "/invoke/v1/" + path,
                          data=None if body is None else json.dumps(body).encode(),
                          headers=headers, method=method)
        with urlopen(request, timeout=self.timeout) as response:
            data = response.read()
            return json.loads(data) if data else None

    @staticmethod
    def _inv(invocation_id: str) -> str:
        return "invocations/" + quote(invocation_id, safe="")

    def capabilities(self, slug: str) -> dict:
        return self._request("GET", "endpoints/" + quote(slug, safe="") + "/capabilities")

    def submit(self, slug: str, input: Any, *, idempotency_key: str,
               title: str = "API request", description: str = "") -> dict:
        return self._request("POST", "endpoints/" + quote(slug, safe="") + "/jobs",
                             {"title": title, "description": description, "input": input}, idempotency_key)

    def converse(self, slug: str, message: str, *, idempotency_key: str,
                 conversation_id: str = "") -> dict:
        path = ("conversations/" + quote(conversation_id, safe="") + "/turns" if conversation_id
                else "endpoints/" + quote(slug, safe="") + "/conversations")
        return self._request("POST", path, {"message": message}, idempotency_key)

    def invocation(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id))

    def snapshot(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/snapshot")

    def events(self, invocation_id: str, *, after: str = "", limit: int = 100) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/events?" + urlencode({"after": after, "limit": limit}))

    def stream(self, invocation_id: str, *, after: str = "") -> Iterator[dict]:
        """Yield committed events. Persist each event's cursor after applying it.

        Network timeouts are raised to the caller; reconnect using the last applied
        cursor. To rebuild a lost UI, load snapshot first and pass its as_of cursor.
        """
        headers = {**self._headers(), "Accept": "text/event-stream"}
        if after:
            headers["Last-Event-ID"] = after
        request = Request(self.base_url + "/invoke/v1/" + self._inv(invocation_id) + "/events/stream", headers=headers)
        with urlopen(request, timeout=self.timeout) as response:
            data: list[str] = []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if data:
                        yield json.loads("\n".join(data))
                    data = []
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip(" "))

    def command(self, invocation_id: str, kind: str, payload: dict | None = None,
                *, idempotency_key: str) -> dict:
        if kind not in {"actions", "inputs", "cancel", "resume"}:
            raise ValueError("unsupported command kind")
        return self._request("POST", self._inv(invocation_id) + "/" + kind, payload or {}, idempotency_key)

    def command_status(self, invocation_id: str, command_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/commands/" + quote(command_id, safe=""))

    def webhook(self, invocation_id: str, url: str, *, idempotency_key: str,
                event_types: list[str] | None = None) -> dict:
        return self._request("POST", self._inv(invocation_id) + "/webhooks",
                             {"url": url, "event_types": event_types or []}, idempotency_key)

    def actions(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/actions")

    def usage(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/usage")

    def artifacts(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/artifacts")

    def artifact(self, invocation_id: str, artifact_id: str) -> bytes:
        request = Request(self.base_url + "/invoke/v1/" + self._inv(invocation_id)
                          + "/artifacts/" + quote(artifact_id, safe=""), headers=self._headers())
        with urlopen(request, timeout=self.timeout) as response:
            return response.read()

    def webhooks(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/webhooks")

    def delete_webhook(self, invocation_id: str, webhook_id: str) -> Any:
        return self._request("DELETE", self._inv(invocation_id) + "/webhooks/" + quote(webhook_id, safe=""))

    def retry_webhook(self, invocation_id: str, webhook_id: str) -> dict:
        return self._request("POST", self._inv(invocation_id) + "/webhooks/" + quote(webhook_id, safe="") + "/retry", {})

    def invocation_capabilities(self, invocation_id: str) -> dict:
        return self._request("GET", self._inv(invocation_id) + "/capabilities")

    def conversation(self, conversation_id: str) -> dict:
        return self._request("GET", "conversations/" + quote(conversation_id, safe=""))
