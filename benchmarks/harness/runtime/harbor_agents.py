# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Harbor adapters preserve native agent loops; installation is frozen into the sandbox image."""
import json
import shlex

from harbor.agents.capabilities import AgentCapabilities
from harbor.agents.installed.base import BaseInstalledAgent
from harbor.agents.installed.codex import Codex
from harbor.agents.installed.pi import Pi
from harbor.agents.options import InstalledAgentOptions
from pydantic import Field
from runtime.protocol import validate_request
from runtime.scenarios import ScenarioMixin, SKILL_REGISTRY


def record_request(agent, instruction):
    value = {"events": [{"type": "user.message", "content": [{"type": "text", "text": instruction}]}]}
    validate_request(value)
    path = agent.logs_dir / "request.json"
    path.write_text(json.dumps(value))
    return path


async def check_version(agent, environment):
    result = await environment.exec(agent.get_version_command(), timeout_sec=30)
    actual = agent.parse_version(result.stdout or "") if result.return_code == 0 else None
    if actual != agent.version():
        raise RuntimeError(f"Baked {agent.name()} version mismatch: expected {agent.version()}, got {actual}")
    (agent.logs_dir / "version.json").write_text(json.dumps({"agent": agent.name(), "actual_version": actual}) + "\n")


class FrozenCodex(ScenarioMixin, Codex):
    eval_name = 'codex'
    async def run_native(self, instruction, environment, context):
        record_request(self, instruction)
        await Codex.run(self, instruction, environment, context)

    async def install(self, environment):
        await check_version(self, environment)


class FrozenPi(ScenarioMixin, Pi):
    eval_name = 'pi'

    def _build_custom_models_json(self, access, model_id):
        value = super()._build_custom_models_json(access, model_id)
        if value and self.eval_spec.get('evidence') == 'compaction':
            for provider in value['providers'].values():
                for model in provider['models']:
                    model.update(contextWindow=12000, maxTokens=4096)
        return value

    async def run_native(self, instruction, environment, context):
        record_request(self, instruction)
        if self.eval_spec.get('evidence') == 'compaction':
            settings = self.logs_dir / 'pi-settings.json'
            settings.write_text(json.dumps({'compaction': {'enabled': True, 'reserveTokens': 4096, 'keepRecentTokens': 2000}}))
            await environment.exec('mkdir -p /tmp/harbor-pi-agent')
            await environment.upload_file(settings, '/tmp/harbor-pi-agent/settings.json')
        await Pi.run(self, instruction, environment, context)

    async def install(self, environment):
        if self.mcp_servers:
            raise ValueError("This frozen image profile does not include the optional Pi MCP extension")
        await check_version(self, environment)


class AgentScopeOptions(InstalledAgentOptions):
    max_turns: int = Field(default=60, ge=1)


class AgentScope(ScenarioMixin, BaseInstalledAgent):
    eval_name = "agentscope"
    capabilities = AgentCapabilities(base_url=True)
    MODEL_CONNECTION = Pi.MODEL_CONNECTION
    options_model = AgentScopeOptions

    @staticmethod
    def name():
        return "agentscope"

    def get_version_command(self):
        return "java -cp '/opt/harness/classes:/opt/harness/dependency/*' io.agentscope.examples.eval.HarnessEvalCli --version"

    def parse_version(self, stdout):
        return stdout.strip().splitlines()[-1].split()[-1]

    async def install(self, environment):
        await check_version(self, environment)

    async def run_native(self, instruction, environment, context):
        request = record_request(self, instruction)
        remote_request = str(self.environment_logs_dir / "request.json")
        native = str(self.environment_logs_dir / self.native_filename())
        await environment.upload_file(request, remote_request)
        connection = self.model_connection
        if not connection.api_key:
            raise ValueError("AgentScope requires a model API key")
        env = {"MODEL_ID": self._parsed_model_name, "MODEL_BASE_URL": self._execution_base_url,
               "DASHSCOPE_API_KEY": connection.api_key, "MAX_TURNS": str(self.options.max_turns),
               "EVAL_SESSION_ID": self.eval_session_id,
               "EVAL_USER_ID": "eval-" + self.logs_dir.name,
               "EVAL_SYSTEM_PROMPT": self.eval_spec.get("system_context", ""),
               "EVAL_SKILLS_DIR": SKILL_REGISTRY if self.eval_spec.get('skills_paths') else '',
               "TURN_TIMEOUT_SECONDS": str(self.eval_spec.get('turn_timeout_seconds', 600)),
               "EVAL_STATE_DIR": str(self.environment_logs_dir / "workspace-state")}
        for key, value in self.eval_spec.get("java", {}).items():
            env["EVAL_" + key.upper()] = str(value).lower()
        command = "java -Xmx2048m -cp '/opt/harness/classes:/opt/harness/dependency/*' io.agentscope.examples.eval.HarnessEvalCli "
        command += " ".join(shlex.quote(value) for value in [remote_request, native, self.eval_spec.get("workdir", "/workspace")])
        await self.exec_as_agent(environment, command=command, env=env)
        context.metadata = {"native_profile": "local-project-writable", "native_log": "native.jsonl"}
