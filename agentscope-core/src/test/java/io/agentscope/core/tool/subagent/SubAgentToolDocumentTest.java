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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SubAgentToolDocumentTest {

    @Test
    void aDocumentDeliveredByTheChildReachesTheParentToolResult() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        Msg reply =
                Msg.builder()
                        .name("child")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here is the report.").build(),
                                        document))
                        .build();
        // A remote or proxied child can answer with a document; ReActAgent itself assembles only
        // text, thinking and tool calls, so the reply is supplied directly here.
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("child");
        when(child.call(anyList())).thenReturn(Mono.just(reply));
        when(child.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, reply, true)));
        SubAgentTool tool =
                new SubAgentTool(
                        () -> child, SubAgentConfig.builder().toolName("call_child").build());
        RuntimeContext parent =
                RuntimeContext.builder().userId("merchant").sessionId("parent").build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("message", "fetch the report"))
                                        .runtimeContext(parent)
                                        .emitter(mock(ToolEmitter.class))
                                        .build())
                        .block(Duration.ofSeconds(10));

        assertTrue(
                result.getOutput().stream().anyMatch(block -> block instanceof DocumentBlock),
                "the child's document must not be flattened away");
        assertTrue(
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(text -> text.getText().contains("Here is the report.")));
    }
}
