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
package io.agentscope.builder;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.channel.feishu.FeishuCallbackController;
import io.agentscope.extensions.channel.github.GitHubWebhookController;
import io.agentscope.extensions.channel.gitlab.GitLabWebhookController;
import io.agentscope.extensions.channel.wecom.WeComCallbackController;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = SchedulerApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SchedulerAppContextLoadTest.StubConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "builder.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "builder.internal-token=test-internal-token-must-be-at-least-32-chars",
            "builder.control-plane-url=http://localhost:8081",
            "builder.data-plane-url=http://localhost:8082",
            // Nothing listens on the test control-plane URL, so SchedulerChannelRuntime.start()
            // would sleep through its default 12 retries x 5s backoff before giving up; one
            // attempt fails fast with the same "zero channels" outcome.
            "builder.scheduler.channel-config-retries=1",
            "builder.workspace=${java.io.tmpdir}/agentscope-builder-scheduler-ctx",
            // INIT pre-creates the dp schema required by hibernate.default_schema (see
            // application.yml); without it schema-qualified H2 statements fail.
            "spring.datasource.url=jdbc:h2:mem:schedulerCtx;DB_CLOSE_DELAY=-1;MODE=MYSQL;INIT=CREATE"
                + " SCHEMA IF NOT EXISTS dp",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never"
        })
class SchedulerAppContextLoadTest {

    @Autowired private FeishuCallbackController feishuCallbackController;

    @Autowired private WeComCallbackController weComCallbackController;

    @Autowired private GitHubWebhookController gitHubWebhookController;

    @Autowired private GitLabWebhookController gitLabWebhookController;

    @Test
    void contextLoads() {}

    @Test
    void channelCallbackControllersAreRegistered() {
        // The advertised /api/channels/... callback paths answer 404 with no trace unless the
        // extensions packages are component-scanned; assert the controllers are beans.
        org.junit.jupiter.api.Assertions.assertNotNull(feishuCallbackController);
        org.junit.jupiter.api.Assertions.assertNotNull(weComCallbackController);
        org.junit.jupiter.api.Assertions.assertNotNull(gitHubWebhookController);
        org.junit.jupiter.api.Assertions.assertNotNull(gitLabWebhookController);
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        AgentStateStore agentStateStore() {
            return new InMemoryAgentStateStore();
        }

        @Bean
        BaseStore baseStore() {
            return new InMemoryStore();
        }
    }
}
