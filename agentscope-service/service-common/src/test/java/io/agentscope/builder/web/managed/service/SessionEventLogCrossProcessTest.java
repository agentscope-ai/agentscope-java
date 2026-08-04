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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.BuilderCommonTestApp;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.test.StepVerifier;

@DataJpaTest
@ContextConfiguration(classes = BuilderCommonTestApp.class)
@Import(SessionEventLogCrossProcessTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:sessionEventLog;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "builder.session-event.poll-interval-ms=50"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionEventLogCrossProcessTest {

    @Autowired SessionEventLog eventLog;
    @Autowired SessionEventEntityRepository repository;
    @Autowired DeletedSessionRegistry deletedSessions;

    @Test
    void concurrentAppendProducesUniqueSeq() throws Exception {
        String sessionId = "ses_concurrent";
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < writers; i++) {
            final int idx = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            eventLog.append(sessionId, "agent.message", Map.of("text", "m" + idx));
                        } catch (Throwable t) {
                            error.compareAndSet(null, t);
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(error.get()).isNull();
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(writers);
        assertThat(repository.maxSeq(sessionId)).isEqualTo(writers);
    }

    @Test
    void subscribeSeesEventsAppendedAfterSubscription() {
        String sessionId = "ses_subscribe";
        eventLog.append(sessionId, "session.status_created", Map.of("status", "created"));

        StepVerifier.create(eventLog.subscribe(sessionId, 0L).take(2))
                .thenAwait(Duration.ofMillis(150))
                .then(
                        () ->
                                eventLog.append(
                                        sessionId, "session.status_idle", Map.of("status", "idle")))
                .expectNextMatches(e -> "session.status_created".equals(e.type()))
                .expectNextMatches(e -> "session.status_idle".equals(e.type()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void subscribeAfterCursorSkipsEarlierEvents() {
        String sessionId = "ses_cursor";
        SessionEventDto first =
                eventLog.append(sessionId, "session.status_created", Map.of("status", "created"));

        StepVerifier.create(eventLog.subscribe(sessionId, first.seq()).take(1))
                .thenAwait(Duration.ofMillis(150))
                .then(() -> eventLog.append(sessionId, "user.message", Map.of("text", "hi")))
                .expectNextMatches(e -> "user.message".equals(e.type()) && e.seq() > first.seq())
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    // A turn keeps running for seconds after its session is deleted, so the purge has to
    // reject those late appends or they recreate the rows it just removed.
    @Test
    void purgedSessionDropsLateAppends() {
        String sessionId = "ses_purged";
        eventLog.append(sessionId, "agent.message", Map.of("text", "before"));
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(1);

        eventLog.purgeDeletedSession(sessionId);
        SessionEventDto dropped =
                eventLog.append(sessionId, "agent.message", Map.of("text", "after"));

        assertThat(deletedSessions.isDeleted(sessionId)).isTrue();
        assertThat(dropped.seq()).isEqualTo(-1L);
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).isEmpty();
    }

    @Test
    void clearingTranscriptKeepsTheSessionWritable() {
        String sessionId = "ses_cleared";
        eventLog.append(sessionId, "agent.message", Map.of("text", "old"));

        eventLog.deleteBySessionId(sessionId);
        eventLog.append(sessionId, "agent.message", Map.of("text", "new"));

        assertThat(deletedSessions.isDeleted(sessionId)).isFalse();
        assertThat(repository.findBySessionIdOrderBySeqAsc(sessionId)).hasSize(1);
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ManagedJsonHelper managedJsonHelper(ObjectMapper objectMapper) {
            return new ManagedJsonHelper(objectMapper);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        DeletedSessionRegistry deletedSessionRegistry() {
            return new DeletedSessionRegistry();
        }

        @Bean
        SessionEventLog sessionEventLog(
                SessionEventEntityRepository repository,
                ManagedJsonHelper jsonHelper,
                TransactionTemplate transactionTemplate,
                DeletedSessionRegistry deletedSessions) {
            return new SessionEventLog(
                    repository, jsonHelper, transactionTemplate, deletedSessions, 100L);
        }
    }
}
