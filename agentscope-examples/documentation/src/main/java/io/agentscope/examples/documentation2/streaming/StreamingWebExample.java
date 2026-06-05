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
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.examples.documentation2.common.MsgUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * StreamingWebExample - Spring Boot + SSE streaming agent responses.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced {@code legacy.session.JsonFileAgentStateStore} with {@code io.agentscope.core.state.JsonFileAgentStateStore}.</li>
 *   <li>Replaced {@code agent.loadIfExists(session, id)} / {@code agent.saveTo(session, id)}
 *       with {@code .stateStore(session).defaultSessionId(sessionId)} on the builder.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn spring-boot:run -pl agentscope-examples/documentation2 \
 *       -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.StreamingWebExample
 *
 *   curl -N "http://localhost:8080/chat?message=Hello"
 *   curl -N "http://localhost:8080/chat?message=What+is+AI?&amp;sessionId=my-session"
 * </pre>
 */
@SpringBootApplication
public class StreamingWebExample {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(StreamingWebExample.class, args);
    }

    /** REST controller providing the streaming chat and health endpoints. */
    @RestController
    public static class ChatController implements InitializingBean {

        private String apiKey;
        private Path sessionPath;

        @Override
        public void afterPropertiesSet() {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }

            sessionPath =
                    Paths.get(
                            System.getProperty("user.home"),
                            ".agentscope",
                            "examples",
                            "web-sessions");

            System.out.println("\n=== StreamingWeb Example Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl -N \"http://localhost:8080/chat?message=Hello\"");
            System.out.println(
                    "  curl -N"
                        + " \"http://localhost:8080/chat?message=What+is+AI&sessionId=my-session\"");
            System.out.println("\nPress Ctrl+C to stop.\n");
        }

        /**
         * Chat endpoint that streams agent responses via SSE.
         *
         * @param message   user message
         * @param sessionId session identifier (defaults to {@code "default"})
         * @return flux of incremental text chunks
         */
        @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> chat(
                @RequestParam String message,
                @RequestParam(defaultValue = "default") String sessionId) {

            AgentStateStore stateStore = new JsonFileAgentStateStore(sessionPath);

            // AgentStateStore is loaded automatically on first call when configured on the builder.
            // The agent saves state after every successful call() and on graceful shutdown.
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("WebAgent")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-plus")
                                            .stream(true)
                                            .build())
                            .stateStore(stateStore)
                            .defaultSessionId(sessionId)
                            .build();

            Msg userMsg = new UserMessage(message);

            StreamOptions streamOptions =
                    StreamOptions.builder()
                            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                            .incremental(true)
                            .includeReasoningResult(false)
                            .build();

            return agent.stream(userMsg, streamOptions)
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(event -> MsgUtils.getTextContent(event.getMessage()))
                    .filter(text -> text != null && !text.isEmpty());
        }

        /**
         * Health check endpoint.
         *
         * @return {@code "OK"}
         */
        @GetMapping("/health")
        public String health() {
            return "OK";
        }
    }
}
