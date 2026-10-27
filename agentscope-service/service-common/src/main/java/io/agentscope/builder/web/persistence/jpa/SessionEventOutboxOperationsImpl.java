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
package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.MANDATORY)
public class SessionEventOutboxOperationsImpl implements SessionEventOutboxOperations {
    private final EntityManager entityManager;

    public SessionEventOutboxOperationsImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public SessionEventOutboxEntity insertIfAbsent(SessionEventOutboxEntity event) {
        JpaPrimaryKeyUpsert.execute(
                entityManager,
                SessionEventOutboxEntity.class,
                "event_id",
                "event_id, session_id, attempt_id, event_seq, report_json, created_at,"
                        + " delivered_at, next_attempt_at, lease_until, attempts, version",
                event.eventId,
                event.sessionId,
                event.attemptId,
                event.eventSeq,
                event.reportJson,
                event.createdAt,
                0L,
                0L,
                0L,
                0,
                0L);
        return entityManager.find(
                SessionEventOutboxEntity.class, event.eventId, LockModeType.PESSIMISTIC_WRITE);
    }
}
