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
package io.agentscope.builder.portal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortalEventRepository extends JpaRepository<PortalEventEntity, String> {
    @Query("select coalesce(max(e.seq), 0) from PortalEventEntity e where e.sessionId = :session")
    long highWatermark(@Param("session") String session);

    List<PortalEventEntity> findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
            String session, long after, long through, Pageable page);

    Optional<PortalEventEntity> findBySessionIdAndId(String session, String id);

    List<PortalEventEntity>
            findBySessionIdAndTurnIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                    String session, String turn, long after, long through, Pageable page);

    long countBySessionIdAndTurnIdAndSeqGreaterThanAndSeqLessThanEqual(
            String session, String turn, long after, long through);

    @Query(
            "select e.seq from PortalEventEntity e where e.sessionId = :session"
                    + " and e.seq > :after and e.seq <= :through order by e.seq")
    List<Long> exportSequences(
            @Param("session") String session,
            @Param("after") long after,
            @Param("through") long through,
            Pageable page);

    @Query(
            "select e.seq from PortalEventEntity e where e.sessionId = :session"
                    + " and e.turnId = :turn and e.seq > :after and e.seq <= :through"
                    + " order by e.seq")
    List<Long> exportTurnSequences(
            @Param("session") String session,
            @Param("turn") String turn,
            @Param("after") long after,
            @Param("through") long through,
            Pageable page);
}
