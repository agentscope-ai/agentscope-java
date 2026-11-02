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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ContextToolkitTest {

    private static class SharedTool {
        @Tool(description = "shared tool")
        public String sharedEcho(String text) {
            return "shared:" + text;
        }
    }

    private static class ScopedTool {
        @Tool(description = "scoped tool")
        public String scopedEcho(String text) {
            return "scoped:" + text;
        }
    }

    private static final class RecordingModel implements Model {
        private final List<Set<String>> visibleTools = new ArrayList<>();

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            visibleTools.add(
                    tools == null
                            ? Set.of()
                            : tools.stream().map(ToolSchema::getName).collect(Collectors.toSet()));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private ReActAgent agent(RecordingModel model, Toolkit shared) {
        return ReActAgent.builder()
                .name("scoped")
                .model(model)
                .toolkit(shared)
                .toolkitForContext(
                        (agentToolkit, context) -> {
                            if (!"alice".equals(context.getUserId())) {
                                return null;
                            }
                            Toolkit scoped = new Toolkit();
                            scoped.registerTool(new ScopedTool());
                            return scoped;
                        })
                .build();
    }

    private static RuntimeContext context(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s").build();
    }

    @Test
    void selectorReplacesTheToolSurfaceForOneContextOnly() {
        var shared = new Toolkit();
        shared.registerTool(new SharedTool());
        var model = new RecordingModel();
        var agent = agent(model, shared);

        agent.call(List.of(new UserMessage("hi")), context("alice")).block(Duration.ofSeconds(10));
        agent.call(List.of(new UserMessage("hi")), context("bob")).block(Duration.ofSeconds(10));

        assertEquals(Set.of("scopedEcho"), model.visibleTools.get(0));
        assertEquals(Set.of("sharedEcho"), model.visibleTools.get(1));
    }

    @Test
    void unsetSelectorKeepsTheSharedToolkit() {
        var shared = new Toolkit();
        shared.registerTool(new SharedTool());
        var model = new RecordingModel();
        var agent = ReActAgent.builder().name("plain").model(model).toolkit(shared).build();

        agent.call(List.of(new UserMessage("hi")), context("alice")).block(Duration.ofSeconds(10));

        assertEquals(Set.of("sharedEcho"), model.visibleTools.get(0));
        assertTrue(shared.getToolNames().contains("sharedEcho"));
        assertFalse(shared.getToolNames().contains("scopedEcho"));
    }
}
