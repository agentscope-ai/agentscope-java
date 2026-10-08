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

"""Platform-token management API, separate from application-scoped invocation keys."""
from __future__ import annotations

from typing import Any
from urllib.parse import quote

from .orchestration import OrchestrationClient


def _id(value: str) -> str:
    return quote(value, safe="")


class ManagementClient(OrchestrationClient):
    """Manage Applications, credentials, Agents, Teams and Workflows.

    Inherits Workflow definitions/revisions, runtime policies and Run operations.
    Request dictionaries follow the public schema without dropping optional fields.
    """

    def applications(self) -> dict:
        return self._send("GET", self._scope("/api/v1/applications", {}))

    def create_application(self, body: dict) -> dict:
        return self._send("POST", "/api/v1/applications", body)

    def application(self, application_id: str) -> dict:
        return self._send("GET", "/api/v1/applications/" + _id(application_id))

    def update_application(self, application_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/applications/" + _id(application_id), body)

    def agents(self, **query: Any) -> dict:
        return self._send("GET", self._scope("/api/v1/agents", query))

    def create_agent(self, body: dict) -> dict:
        return self._send("POST", "/api/v1/agents", body)

    def agent(self, agent_id: str) -> dict:
        return self._send("GET", "/api/v1/agents/" + _id(agent_id))

    def update_agent(self, agent_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/agents/" + _id(agent_id), body)

    def create_binding(self, agent_id: str, body: dict) -> dict:
        return self._send("POST", "/api/v1/agents/" + _id(agent_id) + "/bindings", body)

    def teams(self, **query: Any) -> dict:
        return self._send("GET", self._scope("/api/v1/teams", query))

    def create_team(self, body: dict) -> dict:
        return self._send("POST", "/api/v1/teams", body)

    def team(self, team_id: str) -> dict:
        return self._send("GET", "/api/v1/teams/" + _id(team_id))

    def update_team(self, team_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/teams/" + _id(team_id), body)

    def add_member(self, team_id: str, body: dict) -> dict:
        return self._send("POST", "/api/v1/teams/" + _id(team_id) + "/members", body)

    def credentials(self, application_id: str) -> dict:
        return self._send("GET", "/api/v1/applications/" + _id(application_id) + "/credentials")

    def create_credential(self, application_id: str, *, name: str, scopes: list[str],
                          targets: list[dict], expires_at: str = "") -> dict:
        body = {"name": name, "scopes": scopes, "targets": targets}
        if expires_at:
            body["expiresAt"] = expires_at
        return self._send("POST", "/api/v1/applications/" + _id(application_id) + "/credentials", body)

    def revoke_credential(self, application_id: str, credential_id: str) -> Any:
        return self._send("DELETE", "/api/v1/applications/" + _id(application_id) + "/credentials/" + _id(credential_id))

    def update_member(self, team_id: str, member_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/teams/" + _id(team_id) + "/members/" + _id(member_id), body)

    def remove_member(self, team_id: str, member_id: str) -> dict:
        return self._send("DELETE", "/api/v1/teams/" + _id(team_id) + "/members/" + _id(member_id))
