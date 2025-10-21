/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.tool;

import com.google.gson.Gson;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * ReAct agent example with 3 simple tools and streaming output using Hook.
 * A single user request can trigger multiple tools. Tools:
 * 1) get_time(zone): return current time string
 * 2) get_random(min,max): return a random integer in [min, max]
 * 3) echo(text): echo back the given text
 */
public class ReActAgentWithToolsStreamExample {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentWithToolsStreamExample.class);

    public static class SimpleTools {

        @Tool(name = "get_time", description = "Get current time string of a time zone")
        public String getTime(@ToolParam(name = "zone", description = "Time zone, e.g., Beijing") String zone) {
            LocalDateTime now = LocalDateTime.now();
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        @Tool(name = "get_random", description = "Get a random integer in [min, max]")
        public int getRandom(
                @ToolParam(name = "min", description = "Min value", required = true) Integer min,
                @ToolParam(name = "max", description = "Max value", required = true) Integer max) {
            int lo = min != null ? min : 0;
            int hi = max != null ? max : 100;
            if (hi < lo) {
                int t = lo; lo = hi; hi = t;
            }
            return lo + new Random().nextInt(hi - lo + 1);
        }

        @Tool(name = "echo", description = "Echo back the given text")
        public String echo(@ToolParam(name = "text", description = "Text to echo", required = true) String text) {
            return text == null ? "" : text;
        }
    }

    public static void main(String[] args) throws Exception {
        String dashApiKey = System.getenv("DASHSCOPE_API_KEY");
        if (dashApiKey == null || dashApiKey.isEmpty()) {
            log.warn("No API key found. Please set DASHSCOPE_API_KEY.");
            return;
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        InMemoryMemory memory = new InMemoryMemory();

        Gson gson = new Gson();

        // Create a hook to print streaming messages
        Hook streamingHook = new Hook() {
            @Override
            public Mono<Msg> onReasoning(Agent agent, Msg msg) {
                System.out.println("Reasoning> " + gson.toJson(msg));
                return Mono.just(msg);
            }

            @Override
            public Mono<ToolUseBlock> onToolCall(Agent agent, ToolUseBlock toolUse) {
                System.out.println("ToolCall> " + gson.toJson(toolUse));
                return Mono.just(toolUse);
            }

            @Override
            public Mono<ToolResultBlock> onToolResult(Agent agent, ToolResultBlock toolResult) {
                System.out.println("ToolResult> " + gson.toJson(toolResult));
                return Mono.just(toolResult);
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .name("Friday")
                .sysPrompt("You are a helpful assistant named Friday. You can call tools in parallel when needed.")
                .toolkit(toolkit)
                .memory(memory)
                .model(DashScopeChatModel.builder()
                        .apiKey(dashApiKey)
                        .modelName("qwen-max")
                        .stream(true)
                        .enableThinking(false)
                        .defaultOptions(new GenerateOptions())
                        .build())
                .formatter(new DashScopeChatFormatter())
                .parallelToolCalls(true)
                .hook(streamingHook)
                .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("User> ");
            String line = reader.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                break;
            }
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(line).build();
            Msg finalMsg = agent.call(userMsg).block();
            System.out.println("Final> " + gson.toJson(finalMsg));
        }

        log.info("Bye.");
    }
}


