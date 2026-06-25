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
package io.agentscope.harness.agent.sandbox.impl.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentRunMcpChannelTest {

    @Test
    void parseExecPayloadSupportsFlatJson() {
        AgentRunMcpChannel.ExecResult result =
                AgentRunMcpChannel.parseExecPayload(
                        "{\"exitCode\":0,\"stdout\":\"hello\",\"stderr\":\"\"}");

        assertEquals(0, result.exitCode);
        assertEquals("hello", result.stdout);
        assertEquals("", result.stderr);
    }

    @Test
    void parseExecPayloadSupportsBannerPrefixedNestedJson() {
        String text =
                """
                        ### process_exec_cmd
                        **Sandbox ID (MCP Session ID):** `ac85a2b7-cb2b-497d-a527-5a2d9be8042e`

                        {"status":"completed","result":{"exitCode":1,"stdout":"","stderr":"boom"}}
                        """;

        AgentRunMcpChannel.ExecResult result = AgentRunMcpChannel.parseExecPayload(text);

        assertEquals(1, result.exitCode);
        assertEquals("", result.stdout);
        assertEquals("boom", result.stderr);
    }

    @Test
    void parseExecPayloadFallsBackToPlainText() {
        String text = "### process_exec_cmd\nnot-json";

        AgentRunMcpChannel.ExecResult result = AgentRunMcpChannel.parseExecPayload(text);

        assertEquals(0, result.exitCode);
        assertEquals(text, result.stdout);
        assertEquals("", result.stderr);
    }
}
