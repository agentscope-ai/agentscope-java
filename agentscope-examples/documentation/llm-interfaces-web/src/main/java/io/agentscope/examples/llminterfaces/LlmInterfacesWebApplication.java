/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.llminterfaces;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import reactor.core.publisher.Flux;

/** Example application exposing Chat Completions, Responses, and Anthropic Messages APIs. */
@SpringBootApplication
public class LlmInterfacesWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmInterfacesWebApplication.class, args);
        printStartupInfo();
    }

    @Bean
    public Model demoModel() {
        return new DemoModel();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ReActAgent demoAgent(Model demoModel) {
        return ReActAgent.builder()
                .name("LlmInterfacesDemoAgent")
                .sysPrompt("You are a local demo agent for protocol compatibility testing.")
                .model(demoModel)
                .maxIters(1)
                .build();
    }

    private static void printStartupInfo() {
        System.out.println("\n=== LLM interfaces web example started ===");
        System.out.println("Model discovery:");
        System.out.println("curl http://localhost:8080/v1/models");
        System.out.println("\nOpenAI Chat Completions:");
        System.out.println(
                """
                curl -X POST http://localhost:8080/v1/chat/completions \\
                  -H 'Content-Type: application/json' \\
                  -d '{"model":"demo-agent","messages":[{"role":"user","content":"hello from chat"}]}'
                """);
        System.out.println("\nOpenAI Responses:");
        System.out.println(
                """
                curl -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{"model":"demo-agent","input":"hello from responses"}'
                """);
        System.out.println("\nAnthropic Messages:");
        System.out.println(
                """
                curl -X POST http://localhost:8080/v1/messages \\
                  -H 'Content-Type: application/json' \\
                  -d '{"model":"demo-agent","max_tokens":128,"messages":[{"role":"user","content":"hello from anthropic"}]}'
                """);
        System.out.println("\nFor SSE, add \"stream\": true and use curl -N.");
        System.out.println("==================================================");
    }

    private static final class DemoModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String answer = buildAnswer(messages, tools);
            List<String> chunks = chunk(answer);
            return Flux.range(0, chunks.size())
                    .map(
                            index ->
                                    response(
                                            chunks.get(index),
                                            index == chunks.size() - 1 ? "stop" : null,
                                            answer))
                    .delayElements(Duration.ofMillis(20));
        }

        @Override
        public String getModelName() {
            return "demo-agent";
        }

        private String buildAnswer(List<Msg> messages, List<ToolSchema> tools) {
            String userInput =
                    messages == null
                            ? ""
                            : messages.stream()
                                    .filter(msg -> msg.getRole() == MsgRole.USER)
                                    .map(Msg::getTextContent)
                                    .filter(text -> text != null && !text.isBlank())
                                    .reduce((first, second) -> second)
                                    .orElse("");
            String toolSummary =
                    tools == null || tools.isEmpty()
                            ? "No tool schemas were supplied."
                            : "Tool schemas supplied: "
                                    + tools.stream()
                                            .map(ToolSchema::getName)
                                            .collect(Collectors.joining(", "))
                                    + ".";
            return "Demo response from AgentScope LLM Interfaces. Last user input: "
                    + quote(abbreviate(userInput))
                    + " "
                    + toolSummary;
        }

        private ChatResponse response(String text, String finishReason, String fullAnswer) {
            return ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .usage(
                            ChatUsage.builder()
                                    .inputTokens(8)
                                    .outputTokens(Math.max(1, fullAnswer.length() / 4))
                                    .time(0.0)
                                    .build())
                    .finishReason(finishReason)
                    .build();
        }

        private List<String> chunk(String text) {
            int midpoint = Math.max(1, text.length() / 2);
            return List.of(text.substring(0, midpoint), text.substring(midpoint));
        }

        private String abbreviate(String text) {
            if (text == null || text.isBlank()) {
                return "(empty)";
            }
            return text.length() <= 160 ? text : text.substring(0, 157) + "...";
        }

        private String quote(String text) {
            return "\"" + text.replace("\"", "\\\"") + "\".";
        }
    }
}
