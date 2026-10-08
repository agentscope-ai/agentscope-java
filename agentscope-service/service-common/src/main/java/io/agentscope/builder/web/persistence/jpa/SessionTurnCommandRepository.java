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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionTurnCommandRepository
        extends JpaRepository<SessionTurnCommandEntity, String> {
    List<SessionTurnCommandEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<SessionTurnCommandEntity> findTop100ByStatusInOrderByCreatedAtAsc(
            Collection<String> statuses);

    List<SessionTurnCommandEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionTurnCommandEntity> findTop100ByStatusInAndLeaseUntilLessThanOrderByCreatedAtAsc(
            Collection<String> statuses, long now);

    @Query(
            "select c from SessionTurnCommandEntity c where c.status='queued' and not exists"
                + " (select older.id from SessionTurnCommandEntity older where"
                + " older.sessionId=c.sessionId and older.admissionSeq<c.admissionSeq and"
                + " older.status in"
                + " ('queued','running','cancel_requested','requires_action','interrupted','failed'))"
                + " order by c.createdAt,c.id")
    List<SessionTurnCommandEntity> findDispatchable(org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SessionTurnCommandEntity c where c.id=:id")
    Optional<SessionTurnCommandEntity> lockById(@Param("id") String id);
}
