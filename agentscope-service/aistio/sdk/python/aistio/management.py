"""Platform-token management API, separate from application-scoped invocation keys."""
from __future__ import annotations

from typing import Any
from urllib.parse import quote

from .orchestration import OrchestrationClient


def _id(value: str) -> str:
    return quote(value, safe="")


class ManagementClient(OrchestrationClient):
    """Manage Applications, Agents, Teams, Workflows and published Endpoints.

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

    def endpoints(self, **query: Any) -> dict:
        return self._send("GET", self._scope("/api/v1/endpoints", query))

    def create_endpoint(self, body: dict) -> dict:
        return self._send("POST", "/api/v1/endpoints", body)

    def endpoint(self, endpoint_id: str) -> dict:
        return self._send("GET", "/api/v1/endpoints/" + _id(endpoint_id))

    def update_endpoint(self, endpoint_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/endpoints/" + _id(endpoint_id), body)

    def publish_endpoint(self, endpoint_id: str, *, version: int) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/publish", {"version": version})

    def disable_endpoint(self, endpoint_id: str, *, version: int) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/disable", {"version": version})

    def releases(self, endpoint_id: str) -> dict:
        return self._send("GET", "/api/v1/endpoints/" + _id(endpoint_id) + "/releases")

    def deploy_release(self, endpoint_id: str, body: dict) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/releases", body)

    def rollback_release(self, endpoint_id: str, release_id: str, *, version: int) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/releases/" + _id(release_id) + "/rollback", {"version": version})

    def credentials(self, endpoint_id: str) -> dict:
        return self._send("GET", "/api/v1/endpoints/" + _id(endpoint_id) + "/credentials")

    def create_credential(self, endpoint_id: str, application_id: str, *, name: str,
                          scopes: list[str], expires_at: str = "") -> dict:
        if not scopes:
            raise ValueError("explicit nonempty scopes are required")
        body = {"applicationId": application_id, "name": name, "scopes": scopes}
        if expires_at:
            body["expiresAt"] = expires_at
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/credentials", body)

    def rotate_credential(self, endpoint_id: str, credential_id: str) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/credentials/" + _id(credential_id) + "/rotate", {})

    def revoke_credential(self, endpoint_id: str, credential_id: str) -> dict:
        return self._send("DELETE", "/api/v1/endpoints/" + _id(endpoint_id) + "/credentials/" + _id(credential_id))

    def archive_endpoint(self, endpoint_id: str, *, version: int) -> dict:
        return self._send("DELETE", "/api/v1/endpoints/" + _id(endpoint_id), {"version": version})

    def endpoint_readiness(self, endpoint_id: str) -> dict:
        return self._send("GET", "/api/v1/endpoints/" + _id(endpoint_id) + "/readiness")

    def endpoint_invocations(self, endpoint_id: str, **query: Any) -> dict:
        return self._send("GET", self._scope("/api/v1/endpoints/" + _id(endpoint_id) + "/invocations", query))

    def reveal_credential(self, endpoint_id: str, credential_id: str) -> dict:
        return self._send("POST", "/api/v1/endpoints/" + _id(endpoint_id) + "/credentials/" + _id(credential_id) + "/reveal", {})

    def update_member(self, team_id: str, member_id: str, body: dict) -> dict:
        return self._send("PATCH", "/api/v1/teams/" + _id(team_id) + "/members/" + _id(member_id), body)

    def remove_member(self, team_id: str, member_id: str) -> dict:
        return self._send("DELETE", "/api/v1/teams/" + _id(team_id) + "/members/" + _id(member_id))
