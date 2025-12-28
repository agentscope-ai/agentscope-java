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
