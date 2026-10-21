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
package io.agentscope.builder.hands;

import io.agentscope.builder.runtime.managed.ManagedInfrastructureConfiguration;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Platform Hands owns tool execution facts and reliably publishes completion notifications. */
@SpringBootApplication(scanBasePackages = "io.agentscope.builder.hands")
@Import({ManagedInfrastructureConfiguration.class, VaultCrypto.class})
@EnableConfigurationProperties(BuilderE2bProperties.class)
public class ManagedHandsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ManagedHandsApplication.class, args);
    }
}
