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
package io.agentscope.examples.shutdown.e2e;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for end-to-end testing of graceful shutdown.
 *
 * <p>Usage:
 * <pre>{@code
 * export DASHSCOPE_API_KEY=sk-xxx
 * mvn -pl agentscope-examples/graceful-shutdown spring-boot:run \
 *     -Dspring-boot.run.main-class=io.agentscope.examples.shutdown.e2e.ShutdownE2eApplication
 * }</pre>
 */
@SpringBootApplication
public class ShutdownE2eApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShutdownE2eApplication.class, args);
    }
}
