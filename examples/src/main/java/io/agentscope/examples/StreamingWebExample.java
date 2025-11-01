/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.hook.ChunkMode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.util.MsgUtils;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
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
 * <p>This example demonstrates:
 *
 * <ul>
 *   <li>Spring Boot REST API with reactive endpoints
 *   <li>Server-Sent Events (SSE) for real-time streaming
 *   <li>Agent stream() API for event-based streaming
 *   <li>Session persistence in web environment
 * </ul>
 *
 * <p>Run:
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.StreamingWebExample"
 * </pre>
 *
 * <p>Then open browser:
 *
 * <pre>
 * http://localhost:8080/chat?message=Hello
 * http://localhost:8080/chat?message=What is AI?&sessionId=my-session
 * </pre>
 *
 * <p>Or use curl:
 *
 * <pre>
 * curl -N "http://localhost:8080/chat?message=Hello"
 * </pre>
 */
@SpringBootApplication
public class StreamingWebExample {

    public static void main(String[] args) {
        SpringApplication.run(StreamingWebExample.class, args);
    }

    @RestController
    public static class ChatController implements InitializingBean {

        private String apiKey;
        private JsonSession session;

        @Override
        public void afterPropertiesSet() throws Exception {
            // Get API key from environment
            apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                System.err.println(
                        "Error: DASHSCOPE_API_KEY environment variable not set. Please set it"
                                + " before starting.");
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }

            // Initialize session manager
            session =
                    new JsonSession(
                            Paths.get(
                                    System.getProperty("user.home"),
                                    ".agentscope",
                                    "examples",
                                    "web-sessions"));

            System.out.println("\n=== StreamingWeb Example Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl -N \"http://localhost:8080/chat?message=Hello\"");
            System.out.println(
                    "  curl -N"
                        + " \"http://localhost:8080/chat?message=What%20is%20AI?&sessionId=my-session\"");
            System.out.println("\nPress Ctrl+C to stop.\n");
        }

        /**
         * Chat endpoint with SSE streaming.
         *
         * @param message User message
         * @param sessionId Session ID (optional, defaults to "default")
         * @return Flux of streaming text chunks
         */
        @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> chat(
                @RequestParam String message,
                @RequestParam(defaultValue = "default") String sessionId) {

            // Create agent components
            InMemoryMemory memory = new InMemoryMemory();
            Toolkit toolkit = new Toolkit();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("WebAgent")
                            .sysPrompt(
                                    "You are a helpful AI assistant. Provide clear and concise"
                                            + " answers.")
                            .toolkit(toolkit)
                            .memory(memory)
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-plus")
                                            .stream(true) // Enable streaming
                                            .enableThinking(true)
                                            .formatter(new DashScopeChatFormatter())
                                            .build())
                            .build();

            // Create state modules for session
            Map<String, StateModule> stateModules = new HashMap<>();
            stateModules.put("agent", agent);
            stateModules.put("memory", memory);

            // Load session if exists
            if (session.sessionExists(sessionId)) {
                session.loadSessionState(sessionId, stateModules);
            }

            // Create user message
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(message).build())
                            .build();

            // Configure streaming options - INCREMENTAL mode for SSE
            StreamOptions streamOptions =
                    StreamOptions.builder()
                            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                            .chunkMode(ChunkMode.INCREMENTAL)
                            .build();

            // Use stream() API instead of hooks
            // Subscribe on boundedElastic scheduler for blocking operations
            return agent.stream(userMsg, streamOptions)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(
                            signalType -> {
                                // Save session after completion
                                try {
                                    session.saveSessionState(sessionId, stateModules);
                                } catch (Exception e) {
                                    System.err.println("Failed to save session: " + e.getMessage());
                                }
                            })
                    .doOnError(
                            error -> {
                                // Error handling
                                System.err.println("Agent error: " + error.getMessage());
                            })
                    .map(
                            event -> {
                                // Extract text content from each event
                                return MsgUtils.getTextContent(event.getMessage());
                            })
                    .filter(text -> text != null && !text.isEmpty());
        }

        /**
         * Health check endpoint.
         *
         * @return Health status
         */
        @GetMapping("/health")
        public String health() {
            return "OK";
        }
    }
}
