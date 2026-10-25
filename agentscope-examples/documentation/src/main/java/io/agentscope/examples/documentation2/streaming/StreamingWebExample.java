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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
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
 * <p>A shared builder holds application configuration. Each subscription builds its own agent
 * and closes it when the stream completes, fails, or is cancelled. The shared state store keeps
 * conversation history between requests. Applications must sequence requests for the same session.
 *
 * <p>Usage:
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn spring-boot:run -pl agentscope-examples/documentation \
 *       -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.streaming.StreamingWebExample
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

        private ReActAgent.Builder agentBuilder;

        @Override
        public void afterPropertiesSet() {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }

            Path sessionPath =
                    Paths.get(
                            System.getProperty("user.home"),
                            ".agentscope",
                            "examples",
                            "web-sessions");

            agentBuilder =
                    ReActAgent.builder()
                            .name("WebAgent")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-plus")
                                            .stream(true)
                                            .build())
                            .stateStore(new JsonFileAgentStateStore(sessionPath));

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

            // This demo uses anonymous sessions. In production, derive userId from authentication
            // and verify that the caller can access sessionId before starting the stream.
            RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();

            return Flux.using(
                            agentBuilder::build,
                            agent -> agent.streamEvents(new UserMessage(message), ctx),
                            ReActAgent::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    .filter(event -> event instanceof TextBlockDeltaEvent)
                    .map(event -> ((TextBlockDeltaEvent) event).getDelta());
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
