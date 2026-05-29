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
package io.agentscope.examples.responses;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application demonstrating how to use {@code
 * agentscope-responses-web-starter}.
 *
 * <p>After starting this app, you can call:
 *
 * <p>Non-streaming request (stream=false or omitted):
 *
 * <pre>
 * curl -X POST http://localhost:8080/v1/responses \\
 *   -H 'Content-Type: application/json' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "stream": false,
 *     "input": "Hello, can you briefly introduce AgentScope Java?"
 *   }'
 * </pre>
 *
 * <p>Streaming request (stream=true, Accept header is optional):
 *
 * <pre>
 * curl -N -X POST http://localhost:8080/v1/responses \\
 *   -H 'Content-Type: application/json' \\
 *   -d '{
 *     "model": "qwen3-max",
 *     "stream": true,
 *     "input": "Please provide a streamed answer: Describe AgentScope Java in three sentences."
 *   }'
 * </pre>
 *
 * <p>JSON Schema structured output is supported in both non-streaming and streaming modes through
 * {@code text.format.type=json_schema}. Stateful Responses and Conversations endpoints are also
 * exposed by the starter.
 */
@SpringBootApplication
public class ResponsesWebApplication {

    /**
     * Start the example application and print manual verification commands.
     *
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ResponsesWebApplication.class, args);
        printStartupInfo();
    }

    /** Print curl examples that cover the main Responses and Conversations API paths. */
    private static void printStartupInfo() {
        System.out.println("\n=== Responses API Spring Web Example Application Started ===");
        System.out.println("\nBase URL: http://localhost:8080");
        System.out.println(
                "Tip: pipe JSON responses to `python3 -m json.tool` for readable output.");
        printSection(
                "Non-streaming response",
                """
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "input": "Please describe AgentScope Java in three sentences.",
                    "store": true
                  }' | tee /tmp/response.json | python3 -m json.tool
                """);
        printSection(
                "Stored response and previous_response_id",
                """
                RESP_ID=$(python3 -c 'import json; print(json.load(open("/tmp/response.json"))["id"])')

                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d "{
                    \\"model\\": \\"qwen3-max\\",
                    \\"previous_response_id\\": \\"${RESP_ID}\\",
                    \\"input\\": \\"Continue from the previous response in one sentence.\\"
                  }" | python3 -m json.tool
                """);
        printSection(
                "Streaming response",
                """
                curl -N -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -H 'Accept: text/event-stream' \\
                  -d '{
                    "model": "qwen3-max",
                    "stream": true,
                    "input": "Please provide a streamed answer in three short sentences."
                  }'
                """);
        System.out.println(
                "Note: Responses streams end with response.completed and do not send [DONE].");
        printSection(
                "JSON Schema structured output",
                """
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "input": "Extract the city and weather from: Hangzhou is hot today.",
                    "text": {
                      "format": {
                        "type": "json_schema",
                        "name": "weather_extract",
                        "strict": true,
                        "schema": {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string" },
                            "weather": { "type": "string" }
                          },
                          "required": ["city", "weather"],
                          "additionalProperties": false
                        }
                      }
                    }
                  }' | tee /tmp/schema-response.json | python3 -m json.tool
                """);
        printSection(
                "Structured output streaming",
                """
                curl -N -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -H 'Accept: text/event-stream' \\
                  -d '{
                    "model": "qwen3-max",
                    "stream": true,
                    "input": "Extract the city and weather from: Hangzhou is hot today.",
                    "text": {
                      "format": {
                        "type": "json_schema",
                        "name": "weather_extract",
                        "schema": {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string" },
                            "weather": { "type": "string" }
                          },
                          "required": ["city", "weather"],
                          "additionalProperties": false
                        }
                      }
                    }
                  }'
                """);
        printSection(
                "Background response, retrieve, cancel, and delete",
                """
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "background": true,
                    "store": true,
                    "input": "Write a longer AgentScope Java overview."
                  }' | tee /tmp/background-response.json | python3 -m json.tool

                BG_ID=$(python3 -c 'import json; print(json.load(open("/tmp/background-response.json"))["id"])')
                curl -s "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
                curl -s -X POST "http://localhost:8080/v1/responses/${BG_ID}/cancel" | python3 -m json.tool
                curl -s -X DELETE "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
                """);
        printSection(
                "Conversation state and items",
                """
                curl -s -X POST http://localhost:8080/v1/conversations \\
                  -H 'Content-Type: application/json' \\
                  -d '{"metadata":{"user":"alice"}}' \\
                  | tee /tmp/conversation.json | python3 -m json.tool

                CONV_ID=$(python3 -c 'import json; print(json.load(open("/tmp/conversation.json"))["id"])')
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d "{
                    \\"model\\": \\"qwen3-max\\",
                    \\"conversation\\": \\"${CONV_ID}\\",
                    \\"input\\": \\"Add this message to the conversation.\\",
                    \\"store\\": true
                  }" | python3 -m json.tool

                curl -s "http://localhost:8080/v1/conversations/${CONV_ID}/items?limit=10&order=asc" \\
                  | python3 -m json.tool
                """);
        printSection(
                "Multimodal and file-reference input",
                """
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "input": [{
                      "type": "message",
                      "role": "user",
                      "content": [
                        { "type": "input_text", "text": "Describe this image." },
                        {
                          "type": "input_image",
                          "image_url": "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3f/Fronalpstock_big.jpg/640px-Fronalpstock_big.jpg"
                        },
                        { "type": "input_file", "file_id": "file_test_123" }
                      ]
                    }]
                  }' | python3 -m json.tool
                """);
        System.out.println(
                "Note: image/audio understanding depends on the selected multimodal model and"
                        + " model adapter.");
        System.out.println(
                "      file_id inputs require an application file service for real file content"
                        + " parsing.");
        printSection(
                "External function tool loop",
                """
                curl -s -X POST http://localhost:8080/v1/responses \\
                  -H 'Content-Type: application/json' \\
                  -d '{
                    "model": "qwen3-max",
                    "input": "Call get_weather for Hangzhou, then wait for the tool result.",
                    "tools": [{
                      "type": "function",
                      "name": "get_weather",
                      "description": "Get the current weather for a city",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "city": { "type": "string", "description": "The city name" }
                        },
                        "required": ["city"],
                        "additionalProperties": false
                      },
                      "strict": true
                    }],
                    "tool_choice": { "type": "function", "name": "get_weather" },
                    "store": true
                  }' | tee /tmp/tool-call-response.json | python3 -m json.tool
                """);
        System.out.println(
                "Note: request-level tools are schema-only external tools. Execute the tool in"
                        + " the client,");
        System.out.println("      then send a function_call_output item in the next request.");
        System.out.println(
                "      To execute Java methods inside the backend, register @Tool methods in the"
                        + " application Toolkit.");
        System.out.println("===================================================");
    }

    /** Print one titled command section in the startup banner. */
    private static void printSection(String title, String command) {
        System.out.println("\n===================================================");
        System.out.println(title + "\n");
        System.out.println(command);
    }
}
