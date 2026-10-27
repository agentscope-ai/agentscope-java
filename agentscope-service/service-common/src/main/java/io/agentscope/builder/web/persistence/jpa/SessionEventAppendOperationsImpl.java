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
import jakarta.persistence.Tuple;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes event writes through a stable per-session counter. */
@Transactional(propagation = Propagation.MANDATORY)
public class SessionEventAppendOperationsImpl implements SessionEventAppendOperations {
    private static final String SEQUENCES = "{h-schema}builder_session_event_sequence";
    private static final String IDENTITIES = "{h-schema}builder_session_event_identity";
    private final EntityManager entityManager;

    public SessionEventAppendOperationsImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    private long lockNextSequence(String sessionId) {
        JpaPrimaryKeyUpsert.execute(
                entityManager,
                SessionEventSequenceEntity.class,
                "session_id",
                "session_id",
                sessionId);
        // A locking read sees the current counter even in a MySQL REPEATABLE_READ caller.
        Number next =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "select next_seq from "
                                                + SEQUENCES
                                                + " where session_id = :sid for update")
                                .setParameter("sid", sessionId)
                                .getSingleResult();
        if (next != null) return next.longValue();
        // Only legacy logs can contain events without identity rows. Old writers must be stopped
        // before upgrading; new writers and transcript clearing already hold this counter lock.
        long initial = 1;
        for (var event :
                entityManager
                        .createQuery(
                                "select e.rowId as rowId, e.eventId as eventId, e.seq as seq from"
                                        + " SessionEventEntity e where e.sessionId = :sid",
                                Tuple.class)
                        .setParameter("sid", sessionId)
                        .getResultList()) {
            String eventId = event.get("eventId", String.class);
            lockIdentity(eventId);
            bindIdentity(eventId, sessionId, event.get("rowId", Long.class));
            initial = Math.max(initial, event.get("seq", Long.class) + 1);
        }
        setNextSequence(sessionId, initial);
        return initial;
    }

    private void setNextSequence(String sessionId, long nextSeq) {
        entityManager
                .createNativeQuery(
                        "update " + SEQUENCES + " set next_seq = :seq where session_id = :sid")
                .setParameter("seq", nextSeq)
                .setParameter("sid", sessionId)
                .executeUpdate();
    }

    private Long lockIdentity(String eventId) {
        JpaPrimaryKeyUpsert.execute(
                entityManager, SessionEventIdentityEntity.class, "event_id", "event_id", eventId);
        Number rowId =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "select event_row_id from "
                                                + IDENTITIES
                                                + " where event_id = :id for update")
                                .setParameter("id", eventId)
                                .getSingleResult();
        return rowId == null ? null : rowId.longValue();
    }

    private void bindIdentity(String eventId, String sessionId, long rowId) {
        entityManager
                .createNativeQuery(
                        "update "
                                + IDENTITIES
                                + " set event_row_id = :row, session_id = :sid where event_id ="
                                + " :id")
                .setParameter("row", rowId)
                .setParameter("sid", sessionId)
                .setParameter("id", eventId)
                .executeUpdate();
    }

    @Override
    public SessionEventEntity appendEvent(SessionEventEntity event) {
        long seq = lockNextSequence(event.getSessionId());
        if (lockIdentity(event.getEventId()) != null)
            throw new IllegalStateException("eventId already exists: " + event.getEventId());
        return insert(event, seq);
    }

    @Override
    public AppendResult appendIdempotentEvent(SessionEventEntity event) {
        long seq = lockNextSequence(event.getSessionId());
        Long rowId = lockIdentity(event.getEventId());
        if (rowId != null) {
            // Read by the existing row's primary key, so stale caller snapshots cannot hide a
            // committed winner and MySQL never locks gaps in the event_id secondary index.
            var winner =
                    entityManager.find(
                            SessionEventEntity.class, rowId, LockModeType.PESSIMISTIC_WRITE);
            if (winner == null)
                throw new IllegalStateException(
                        "Event identity has no event row: " + event.getEventId());
            return new AppendResult(winner, false);
        }
        return new AppendResult(insert(event, seq), true);
    }

    private SessionEventEntity insert(SessionEventEntity event, long seq) {
        event.setSeq(seq);
        entityManager.persist(event);
        entityManager.flush();
        bindIdentity(event.getEventId(), event.getSessionId(), event.getRowId());
        setNextSequence(event.getSessionId(), seq + 1);
        return event;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        lockNextSequence(sessionId);
        // Drop identities before their events so a replay holding an identity lock can still
        // resolve its event row. Both removals and the counter reset commit atomically.
        entityManager
                .createQuery("delete from SessionEventIdentityEntity e where e.sessionId = :sid")
                .setParameter("sid", sessionId)
                .executeUpdate();
        entityManager
                .createQuery("delete from SessionEventEntity e where e.sessionId = :sid")
                .setParameter("sid", sessionId)
                .executeUpdate();
        setNextSequence(sessionId, 1L);
    }
}
