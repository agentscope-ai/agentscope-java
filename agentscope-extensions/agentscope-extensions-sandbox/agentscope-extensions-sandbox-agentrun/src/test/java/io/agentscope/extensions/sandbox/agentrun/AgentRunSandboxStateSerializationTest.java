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

import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentRunSandboxStateSerializationTest {

    @Test
    void roundTripPreservesAllFields() {
        AgentRunSandboxClient client = new AgentRunSandboxClient();

        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-42");
        state.setSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");
        state.setWorkspaceRoot("/mnt/nas/workspace");
        state.setTemplateName("agentscope-default");
        state.setAccountId("123456789012");
        state.setRegion("cn-hangzhou");
        state.setMcpServerUrl("https://example.com/mcp");
        state.setSandboxOwned(true);
        state.setWorkspaceOnNas(true);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("abc123");

        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/mnt/nas/workspace");
        state.setWorkspaceSpec(ws);

        String json = client.serializeState(state);
        SandboxState read = client.deserializeState(json);

        Assertions.assertInstanceOf(AgentRunSandboxState.class, read);
        AgentRunSandboxState r = (AgentRunSandboxState) read;
        Assertions.assertEquals("session-42", r.getSessionId());
        Assertions.assertEquals("01KE8DAJ35JC8SKP9CNFRZ8CW7", r.getSandboxId());
        Assertions.assertEquals("/mnt/nas/workspace", r.getWorkspaceRoot());
        Assertions.assertEquals("agentscope-default", r.getTemplateName());
        Assertions.assertEquals("123456789012", r.getAccountId());
        Assertions.assertEquals("cn-hangzhou", r.getRegion());
        Assertions.assertEquals("https://example.com/mcp", r.getMcpServerUrl());
        Assertions.assertTrue(r.isSandboxOwned());
        Assertions.assertTrue(r.isWorkspaceOnNas());
        Assertions.assertTrue(r.isWorkspaceRootReady());
        Assertions.assertEquals("abc123", r.getWorkspaceProjectionHash());
        Assertions.assertNotNull(r.getWorkspaceSpec());
        Assertions.assertEquals("/mnt/nas/workspace", r.getWorkspaceSpec().getRoot());
    }

    @Test
    void roundTripWithDefaultsKeepsTypeId() {
        AgentRunSandboxClient client = new AgentRunSandboxClient();

        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-default");
        state.setSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW8");
        state.setTemplateName("template-x");

        String json = client.serializeState(state);
        Assertions.assertTrue(json.contains("\"type\":\"agentrun\""), "type id must be present");

        SandboxState read = client.deserializeState(json);
        Assertions.assertInstanceOf(AgentRunSandboxState.class, read);
        AgentRunSandboxState r = (AgentRunSandboxState) read;
        Assertions.assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, r.getWorkspaceRoot());
        Assertions.assertFalse(r.isWorkspaceOnNas());
        Assertions.assertTrue(r.isSandboxOwned());
        Assertions.assertFalse(r.isWorkspaceRootReady());
    }

    @Test
    void deriveSandboxIdIsDeterministicAndUlidShape() {
        String a = AgentRunSandboxClient.deriveSandboxId("session-id-1");
        String b = AgentRunSandboxClient.deriveSandboxId("session-id-1");
        String c = AgentRunSandboxClient.deriveSandboxId("session-id-2");
        Assertions.assertEquals(a, b, "same session must derive the same sandbox id");
        Assertions.assertNotEquals(a, c, "different sessions must derive different sandbox ids");
        Assertions.assertEquals(26, a.length(), "sandbox id must be 26 chars (ULID shape)");
        Assertions.assertTrue(
                a.matches("[0-9A-HJKMNP-TV-Z]+"),
                "sandbox id must use Crockford base32 alphabet but was: " + a);
    }
}
