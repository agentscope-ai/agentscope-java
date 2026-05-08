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
package io.agentscope.examples.harness.remote;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry for the remote-store Data Agent example.
 *
 * <p>Set {@code DASHSCOPE_API_KEY}. Default port {@code 8788} (see {@code application.properties}).
 *
 * <p>This module demonstrates {@link io.agentscope.harness.agent.filesystem.RemoteFilesystemSpec}
 * ({@code docs/zh/harness/filesystem.md} <strong>模式一</strong>). Sandbox (<strong>模式二</strong>)
 * is {@code harness-example-sandbox}.
 */
@SpringBootApplication
public class HarnessRemoteApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessRemoteApplication.class, args);
    }
}
