/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.examples.chatcompletions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application demonstrating how to use
 * {@code agentscope-chat-completions-web-starter}.
 *
 * <p>After starting this app, you can call:
 *
 * <pre>
 * curl -X POST http://localhost:8080/v1/chat/completions \\
 *   -H 'Content-Type: application/json' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "sessionId": "demo-user",
 *     "messages": [
 *       { "role": "user", "content": "Hello, can you briefly introduce AgentScope Java?" }
 *     ]
 *   }'
 * </pre>
 *
 * Or streaming (SSE):
 *
 * <pre>
 * curl -N http://localhost:8080/v1/chat/completions \\
 *   -H 'Content-Type: application/json' \\
 *   -H 'Accept: text/event-stream' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "stream": true,
 *     "sessionId": "demo-user",
 *     "messages": [
 *       { "role": "user", "content": "Please provide a streamed answer: Describe AgentScope Java in three sentences." }
 *     ]
 *   }'
 * </pre>
 */
@SpringBootApplication
public class ChatCompletionsWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatCompletionsWebApplication.class, args);
        printStartupInfo();
    }

    private static void printStartupInfo() {
        System.out.println("\n=== chat completions API spring web Example Application Started ===");
        System.out.println("\nExample curl command:");
        System.out.println("\nNon-streaming chat completion.\n");
        System.out.println(
                """
                curl -X POST http://localhost:8080/v1/chat/completions \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "sessionId": "demo-user",
                    "messages": [
                      { "role": "user", "content": "Please provide a Non streamed answer: Describe AgentScope Java in three sentences." }
                    ]
                  }'
                """);
        System.out.println("===================================================");
        System.out.println("\nStreaming chat completion.\n");
        System.out.println(
                """
                curl -N http://localhost:8080/v1/chat/completions \\
                  -H 'Content-Type: application/json' \\
                  -H 'Accept: text/event-stream' \\
                  -d '{
                    "model": "qwen3-max",
                    "sessionId": "demo-user",
                    "messages": [
                      { "role": "user", "content": "Please provide a streamed answer: Describe AgentScope Java in three sentences." }
                    ]
                  }'
                """);
    }
}
