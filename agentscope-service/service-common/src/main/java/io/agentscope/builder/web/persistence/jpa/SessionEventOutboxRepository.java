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

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionEventOutboxRepository
        extends JpaRepository<SessionEventOutboxEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SessionEventOutboxEntity e where e.eventId=:id")
    Optional<SessionEventOutboxEntity> lockById(@Param("id") String id);

    @Query(
            "select e from SessionEventOutboxEntity e where e.deliveredAt=0 and"
                    + " e.nextAttemptAt<=:now and e.leaseUntil<=:now and not exists (select"
                    + " older.eventId from SessionEventOutboxEntity older where"
                    + " older.sessionId=e.sessionId and older.eventSeq<e.eventSeq and"
                    + " older.deliveredAt=0) order by e.createdAt,e.eventSeq")
    List<SessionEventOutboxEntity> findDeliverable(@Param("now") long now, Pageable page);

    Optional<SessionEventOutboxEntity> findFirstBySessionIdAndDeliveredAtOrderByEventSeqAsc(
            String sessionId, long deliveredAt);

    List<SessionEventOutboxEntity> findBySessionIdAndAttemptIdOrderByEventSeqAsc(
            String sessionId, String attemptId);
}
