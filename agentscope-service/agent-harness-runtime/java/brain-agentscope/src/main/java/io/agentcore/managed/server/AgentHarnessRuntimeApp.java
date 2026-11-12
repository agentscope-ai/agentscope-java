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
package io.agentcore.managed.server;

import io.agentcore.managed.server.runtime.RemoteBrainConfiguration;
import io.agentcore.managed.server.web.config.E2bProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the AgentScope Brain.
 *
 * <p>Accepts authenticated task notifications and claims durable executions, builds agents from admitted snapshots and runs them
 * through the AgentScope adapter. Session Service owns durable commits; Hands executes remote
 * tools. Session APIs and SSE are hosted by Agent Service.
 *
 * <p>Only HTTP Brain components are imported. Platform persistence and MQ consumer
 * configurations are deliberately excluded.
 */
@SpringBootApplication(
        scanBasePackages = "io.agentcore.managed.server.brainhttp",
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
@Import(RemoteBrainConfiguration.class)
@EnableConfigurationProperties(E2bProperties.class)
@EnableScheduling
public class AgentHarnessRuntimeApp {
    public static void main(String[] args) {
        SpringApplication.run(AgentHarnessRuntimeApp.class, args);
    }
}
