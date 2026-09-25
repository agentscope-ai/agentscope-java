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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link SessionEventEntity}. */
public interface SessionEventEntityRepository extends JpaRepository<SessionEventEntity, Long> {

    List<SessionEventEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    List<SessionEventEntity> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(
            String sessionId, long afterSeq);

    List<SessionEventEntity> findBySessionIdAndEventTypeInOrderBySeqAsc(
            String sessionId, Collection<String> eventTypes);

    List<SessionEventEntity> findBySessionIdAndEventTypeInAndSeqGreaterThanOrderBySeqAsc(
            String sessionId, Collection<String> eventTypes, long afterSeq);

    Optional<SessionEventEntity> findByEventId(String eventId);

    @Query("select coalesce(max(e.seq), 0) from SessionEventEntity e where e.sessionId = :sid")
    long maxSeq(@Param("sid") String sessionId);

    @Query(
            "select count(e) from SessionEventEntity e where e.sessionId = :sid and e.eventType in"
                    + " :types")
    long countBySessionIdAndEventTypeIn(
            @Param("sid") String sessionId, @Param("types") Collection<String> types);

    @Query(
            "select coalesce(max(e.createdAt), 0) from SessionEventEntity e where e.sessionId ="
                    + " :sid")
    long maxCreatedAt(@Param("sid") String sessionId);

    boolean existsBySessionIdAndEventType(String sessionId, String eventType);

    void deleteBySessionId(String sessionId);
}
