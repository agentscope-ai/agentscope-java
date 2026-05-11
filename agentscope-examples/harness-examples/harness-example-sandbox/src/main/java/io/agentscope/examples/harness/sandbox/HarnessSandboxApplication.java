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
package io.agentscope.examples.harness.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry for the sandbox Data Agent (text-to-SQL) demo.
 *
 * <p>Set {@code DASHSCOPE_API_KEY} before starting. Optionally override the model with
 * {@code AGENTSCOPE_MODEL} (default: {@code qwen-max}).
 *
 * <p>Once started, send questions via:
 *
 * <pre>
 * curl -X POST http://localhost:8787/query \
 *   -H 'Content-Type: application/json' \
 *   -d '{"sessionId":"s1","userId":"alice","question":"How many artists are there?"}'
 * </pre>
 */
@SpringBootApplication
public class HarnessSandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessSandboxApplication.class, args);
    }
}
