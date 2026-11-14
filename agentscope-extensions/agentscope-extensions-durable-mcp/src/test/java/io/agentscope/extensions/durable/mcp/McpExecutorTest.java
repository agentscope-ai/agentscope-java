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

package io.agentscope.extensions.durable.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpExecutorTest {
    @Test
    void metadataPermissionAndRawStructuredResultsArePreservedWithoutUnsafeRetry() {
        var client = mock(McpClientWrapper.class);
        when(client.isInitialized()).thenReturn(true);
        when(client.isPropagateMeta()).thenReturn(false);
        var result =
                DurableJson.convert(
                        DurableJson.parse(
                                """
                                {"content":[{"type":"text","text":"ok"}],"isError":false,"structuredContent":{"answer":42}}
                                """),
                        McpSchema.CallToolResult.class);
        when(client.callTool(eq("remote"), anyMap(), isNull())).thenReturn(Mono.just(result));
        var executor =
                new McpExecutor(
                        request -> new McpExecutor.Target(client, "remote", Map.of("value", 7)),
                        Duration.ofSeconds(1));
        var request =
                new ExecutionRequest(
                        "execution",
                        new ThreadKey(new SessionKey("tenant", "session"), "main"),
                        "turn",
                        new ToolCall("call", "mcp", "catalog-alias", DurableJson.value(Map.of())));
        var reply = executor.startExecution(request);
        assertEquals(ExecutionState.SUCCEEDED, reply.state());
        assertEquals(
                42, reply.result().path("result").path("structuredContent").path("answer").asInt());
        assertEquals(ExecutionState.UNKNOWN, executor.query(request, null).state());
        assertEquals(ExecutionState.UNKNOWN, executor.cancel(request, null).state());
        assertFalse(executor.capabilities().idempotentStart());
        verify(client, times(1)).callTool(eq("remote"), anyMap(), isNull());
        verify(client, never()).close();
    }
}
