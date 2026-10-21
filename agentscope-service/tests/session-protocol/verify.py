"""Fixed official SDK against the isolated real HTTP/auth/JPA contract fixture."""
import sys
from anthropic import Anthropic
from anthropic.types.beta.beta_managed_agents_session import BetaManagedAgentsSession
from anthropic.types.beta.sessions.beta_managed_agents_user_message_event import BetaManagedAgentsUserMessageEvent

base_url, api_key, expected = sys.argv[1:]
client = Anthropic(base_url=base_url, api_key=api_key, max_retries=0, _strict_response_validation=True)
session = client.beta.sessions.create(agent="agent", environment_id="environment")
BetaManagedAgentsSession.model_validate(session.model_dump())
assert session.id == expected
assert session.agent.execution_identity.type == "service_account"
assert session.agent.tools[0].type == "agent_toolset_20260401"
assert session.agent.tools[0].default_config.enabled is False
assert session.agent.tools[1].type == "mcp_toolset"
assert session.agent.tools[1].default_config.permission_policy.type == "always_allow"
assert session.agent.mcp_servers[0].name == "longwait"
retrieved = client.beta.sessions.retrieve(session.id)
assert retrieved.status == "idle"
sent = client.beta.sessions.events.send(session.id, events=[{"type": "user.message", "content": [{"type": "text", "text": "official Python SDK"}]}])
assert len(sent.data) == 1
BetaManagedAgentsUserMessageEvent.model_validate(sent.data[0].model_dump())
events = client.beta.sessions.events.list(session.id, limit=1)
assert events.data[0].id == sent.data[0].id
assert events.data[0].processed_at is None
print("Official Python SDK 1.15.0: create/retrieve/send/list validated")
