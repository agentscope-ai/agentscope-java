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
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(SessionEventLogCrossProcessTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=${agentscope.test.jdbc-url:jdbc:h2:mem:singleConnection;DB_CLOSE_DELAY=-1}",
            "spring.datasource.username=${agentscope.test.jdbc-username:sa}",
            "spring.datasource.password=${agentscope.test.jdbc-password:}",
            "spring.datasource.hikari.maximum-pool-size=${agentscope.test.pool-size:1}",
            "spring.datasource.hikari.minimum-idle=1",
            "spring.datasource.hikari.connection-timeout=5000",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionEventLogSingleConnectionTest {
    @Autowired SessionEventLog events;
    @Autowired SessionEventEntityRepository repository;
    @Autowired PlatformTransactionManager manager;

    @Test
    void appendUsesTheAdmissionConnectionAndRollsBackWithIt() {
        assertTimeout(
                Duration.ofSeconds(2),
                () -> {
                    events.append("single-connection", "run.started", Map.of());
                    new TransactionTemplate(manager)
                            .executeWithoutResult(
                                    status -> {
                                        assertThat(
                                                        events.append(
                                                                        "single-connection",
                                                                        "item.delta",
                                                                        Map.of())
                                                                .seq())
                                                .isEqualTo(2L);
                                        status.setRollbackOnly();
                                    });
                    assertThat(repository.findBySessionIdOrderBySeqAsc("single-connection"))
                            .hasSize(1);
                    assertThat(events.append("single-connection", "item.delta", Map.of()).seq())
                            .isEqualTo(2L);
                });
    }
}
