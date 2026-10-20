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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.search.ToolSearchMiddleware;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** Offline example: discover a registered tool, then execute it through the ReAct loop. */
public class ToolSearchExample {
    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("orders")
                        .sysPrompt("Find tools before using them.")
                        .toolkit(toolkit)
                        .middleware(new ToolSearchMiddleware(toolkit))
                        .model(new DemonstrationModel())
                        .build();
        Msg response =
                agent.call(
                                "Find my order",
                                RuntimeContext.builder().sessionId("tool-search-example").build())
                        .block();
        System.out.println(response.getTextContent());
    }

    public static class OrderTools {
        @Tool(name = "get_order", description = "Look up the current order", readOnly = true)
        public String getOrder() {
            return "Order 42 is ready to ship.";
        }
    }

    private static final class DemonstrationModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "offline-demonstration";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            System.out.println(
                    "Visible tools: " + tools.stream().map(ToolSchema::getName).toList());
            int call = calls.getAndIncrement();
            ContentBlock block;
            if (call == 0) {
                block =
                        ToolUseBlock.builder()
                                .id("search-1")
                                .name("tool_search")
                                .input(Map.of("query", "order"))
                                .content("{\"query\":\"order\"}")
                                .build();
            } else if (call == 1) {
                block =
                        ToolUseBlock.builder()
                                .id("order-1")
                                .name("get_order")
                                .input(Map.of())
                                .content("{}")
                                .build();
            } else {
                block = TextBlock.builder().text("Order 42 is ready to ship.").build();
            }
            return Flux.just(ChatResponse.builder().content(List.of(block)).build());
        }
    }
}
