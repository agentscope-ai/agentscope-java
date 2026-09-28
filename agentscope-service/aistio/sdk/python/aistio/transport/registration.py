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

"""v5 External Agent registration client."""
from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Iterable
from urllib import error, request


@dataclass(frozen=True)
class RegisteredIdentity:
    """Stable Catalog identity required by every ASDP connection."""

    agent_id: str
    agent_key: str
    binding_id: str
    instance_id: str
    instance_key: str
    generation: int
    registration_credential: str


def register_external_agent(
    control_plane_http: str,
    *,
    bootstrap_token: str,
    registration_credential: str,
    tenant: str,
    namespace: str,
    agent_key: str,
    instance_key: str,
    routing_key: str,
    framework: str,
    sdk_version: str,
    capabilities: Iterable[str],
    timeout: float = 5.0,
) -> RegisteredIdentity:
    """Create or reclaim one External binding/instance through the v5 API."""

    body = json.dumps(
        {
            "tenant": tenant,
            "namespace": namespace,
            "agentKey": agent_key,
            "instanceKey": instance_key,
            "routingKey": routing_key,
            "framework": framework,
            "sdkVersion": sdk_version,
            "capacity": 1,
            "capabilities": list(capabilities),
        }
    ).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    credential = registration_credential or bootstrap_token
    if credential:
        headers["Authorization"] = f"Bearer {credential}"
    req = request.Request(
        control_plane_http.rstrip("/") + "/api/v1/agent-registrations",
        data=body,
        headers=headers,
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=timeout) as response:
            document = json.loads(response.read().decode("utf-8"))
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"agent registration returned HTTP {exc.code}: {detail}") from exc

    agent = document.get("agent") or {}
    binding = document.get("binding") or {}
    instance = document.get("instance") or {}
    issued = document.get("registrationCredential") or registration_credential
    identity = RegisteredIdentity(
        agent_id=str(agent.get("id") or ""),
        agent_key=agent_key,
        binding_id=str(binding.get("id") or ""),
        instance_id=str(instance.get("id") or ""),
        instance_key=instance_key,
        generation=int(instance.get("generation") or 0),
        registration_credential=str(issued or ""),
    )
    if (
        not identity.agent_id
        or not identity.binding_id
        or not identity.instance_id
        or identity.generation <= 0
        or not identity.registration_credential
    ):
        raise RuntimeError("agent registration response is missing stable identity or credential")
    return identity
