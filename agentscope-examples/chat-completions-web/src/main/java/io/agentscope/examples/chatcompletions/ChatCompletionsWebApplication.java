/*
 * Copyright 2024-2026 the original author or authors.
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
 * <p>Non-streaming request (stream=false or omitted):
 *
 * <pre>
 * curl -X POST http://localhost:8080/v1/chat/completions \\
 *   -H 'Content-Type: application/json' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "stream": false,
 *     "messages": [
 *       { "role": "user", "content": "Hello, can you briefly introduce AgentScope Java?" }
 *     ]
 *   }'
 * </pre>
 *
 * <p>Streaming request (stream=true, Accept header is optional):
 *
 * <pre>
 * curl -N -X POST http://localhost:8080/v1/chat/completions \\
 *   -H 'Content-Type: application/json' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "stream": true,
 *     "messages": [
 *       { "role": "user", "content": "Please provide a streamed answer: Describe AgentScope Java in three sentences." }
 *     ]
 *   }'
 * </pre>
 *
 * <p><b>Important:</b> If stream=false but Accept: text/event-stream is set, the request will be
 * rejected with an error for consistency. Use stream=true for streaming, or omit the Accept header
 * for non-streaming.
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
        System.out.println("\nNon-streaming chat completion (stream=false or omitted).\n");
        System.out.println(
                """
                curl -X POST http://localhost:8080/v1/chat/completions \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "stream": false,
                    "messages": [
                      { "role": "user", "content": "Please provide a Non streamed answer: Describe AgentScope Java in three sentences." }
                    ]
                  }'
                """);
        System.out.println("\nNote: stream parameter can be omitted (defaults to false)");
        System.out.println("===================================================");
        System.out.println("\nStreaming chat completion (stream=true).\n");
        System.out.println(
                """
                curl -N -X POST http://localhost:8080/v1/chat/completions \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "stream": true,
                    "messages": [
                      { "role": "user", "content": "Please provide a streamed answer: Describe AgentScope Java in three sentences." }
                    ]
                  }'
                """);
        System.out.println("\nNote: Accept: text/event-stream header is optional when stream=true");
        System.out.println("===================================================");
        System.out.println(
                "\n⚠️  Important: stream=false with Accept: text/event-stream will return error");
        System.out.println(
                "   Use stream=true for streaming, or omit Accept header for non-streaming");
    }
}
