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
package io.agentscope.durable.examples.brainhands.toolinsandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Independent command-line Brain application for remote Sandbox tools. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(SandboxToolProperties.class)
@Import(SandboxBrainExample.class)
public class SandboxBrainApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(SandboxBrainApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var ignored = application.run(args)) {
            // The Brain runner completes before run() returns.
        }
    }
}
