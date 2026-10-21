/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package io.agentscope.builder.web.managed.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.test.StepVerifier;

@DataJpaTest
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(SessionEventLogCrossProcessTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "RUNTIME_TEST_JDBC_URL", matches = ".+")
class SessionEventLogPostgresReplicaTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("RUNTIME_TEST_JDBC_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired DataSource dataSource;
    @Autowired SessionEventLog reader;
    @Autowired SessionEventEntityRepository repository;
    @Autowired ManagedJsonHelper json;
    @Autowired TransactionTemplate tx;
    @Autowired DeletedSessionRegistry deleted;

    @Test
    void differentNotifierReplicasDeliverCommittedDeltasAndReplayFromCursor() throws Exception {
        var notifier = new SessionEventNotifier(dataSource, 100);
        notifier.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                try (var connection = dataSource.getConnection();
                        var statement = connection.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "select count(*) from pg_stat_activity where query='LISTEN"
                                                + " builder_session_events'")) {
                    rows.next();
                    if (rows.getInt(1) >= 2) {
                        ready = true;
                        break;
                    }
                }
                Thread.sleep(20);
            }
            assertThat(ready).isTrue();
            var writer = new SessionEventLog(repository, json, tx, deleted, notifier, 60000);
            String session = "replica-session";
            StepVerifier.create(reader.subscribe(session, 0).take(1))
                    .thenAwait(Duration.ofMillis(150))
                    .then(
                            () ->
                                    writer.append(
                                            session,
                                            "item.delta",
                                            Map.of("content", "shared text", "source", Map.of())))
                    .expectNextMatches(
                            event -> event.type().equals("item.delta") && event.seq() == 1)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
            assertThat(reader.page(session, 0, reader.highWatermark(session), 10)).hasSize(1);
            StepVerifier.create(reader.subscribe(session, 1).take(1))
                    .thenAwait(Duration.ofMillis(150))
                    .then(
                            () ->
                                    writer.append(
                                            session, "item.completed", Map.of("source", Map.of())))
                    .expectNextMatches(
                            event -> event.type().equals("item.completed") && event.seq() == 2)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } finally {
            notifier.stop();
        }
    }
}
