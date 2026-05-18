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
package io.agentscope.examples.chattts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Chat with TTS Application.
 *
 * <p>Demonstrates ReActAgent with real-time Text-to-Speech streaming.
 * The agent's response text and synthesized audio are streamed to the
 * frontend via Server-Sent Events (SSE).
 *
 * <p>Usage:
 * <pre>
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn spring-boot:run -pl agentscope-examples/chat-tts
 * </pre>
 *
 * <p>Then open http://localhost:8080 in your browser.
 */
@SpringBootApplication
public class ChatTTSSpringBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatTTSSpringBootApplication.class, args);
    }
}
