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
package io.agentscope.demo.live;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Live Demo Spring Boot Application.
 *
 * <p>This application provides a web-based interface for real-time voice conversation using
 * LiveAgent. It supports WebSocket connections for bidirectional audio streaming.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * # Set environment variables
 * export DASHSCOPE_API_KEY=your-api-key
 *
 * # Run application
 * cd agentscope-examples/live-demo
 * mvn spring-boot:run
 *
 * # Open browser
 * http://localhost:8080
 * }</pre>
 *
 * <p>Configuration can be customized via application.yml or environment variables:
 *
 * <ul>
 *   <li>LIVE_PROVIDER - Provider name (dashscope, openai, gemini, doubao)
 *   <li>LIVE_MODEL_NAME - Model name
 *   <li>DASHSCOPE_API_KEY / OPENAI_API_KEY / etc. - API key for the provider
 * </ul>
 */
@SpringBootApplication
public class LiveDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveDemoApplication.class, args);
    }
}
