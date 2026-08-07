/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.sandbox.agentrun;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunSandboxTest {

    @Test
    void setupWorkspaceFailsWhenRootCannotBeCreated() throws Exception {
        FakeExecutor mcp = new FakeExecutor();
        mcp.failWorkspaceRootSetup();

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> sandbox(mcp).doSetupWorkspace());

        assertTrue(ex.getMessage().contains("AgentRun workspace root setup failed"));
        assertTrue(ex.getMessage().contains("permission denied"));
    }

    @Test
    void hydrateWorkspaceCreatesRootBeforeExtractingArchive() throws Exception {
        FakeExecutor mcp = new FakeExecutor();

        sandbox(mcp).doHydrateWorkspace(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertTrue(mcp.commands.size() >= 2);
        assertTrue(mcp.commands.get(0).equals("mkdir -p '/home/agentscope/workspace'"));
        assertTrue(mcp.commands.get(1).equals("rm -f /tmp/agentscope-ws.b64"));
    }

    @Test
    void hydrateWorkspaceDoesNotExtractWhenRootCannotBeCreated() throws Exception {
        FakeExecutor mcp = new FakeExecutor();
        mcp.failWorkspaceRootSetup();

        assertThrows(
                SandboxException.SandboxRuntimeException.class,
                () -> sandbox(mcp).doHydrateWorkspace(new ByteArrayInputStream(new byte[] {1})));

        assertTrue(mcp.commands.stream().noneMatch("rm -f /tmp/agentscope-ws.b64"::equals));
    }

    private static AgentRunSandbox sandbox(AgentRunSandbox.AgentRunExecutor mcp) {
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setWorkspaceRoot("/home/agentscope/workspace");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/home/agentscope/workspace");
        state.setWorkspaceSpec(workspaceSpec);
        return new AgentRunSandbox(state, null, null, mcp);
    }

    private static final class FakeExecutor implements AgentRunSandbox.AgentRunExecutor {

        private final List<String> commands = new ArrayList<>();
        private boolean failWorkspaceRootSetup;

        void failWorkspaceRootSetup() {
            this.failWorkspaceRootSetup = true;
        }

        @Override
        public void connect() {}

        @Override
        public AgentRunMcpChannel.ExecResult exec(String command, String cwd, int timeoutSeconds) {
            commands.add(command);
            if (failWorkspaceRootSetup && command.equals("mkdir -p '/home/agentscope/workspace'")) {
                return new AgentRunMcpChannel.ExecResult(2, "", "permission denied");
            }
            return new AgentRunMcpChannel.ExecResult(0, "", "");
        }

        @Override
        public void close() {}
    }
}
