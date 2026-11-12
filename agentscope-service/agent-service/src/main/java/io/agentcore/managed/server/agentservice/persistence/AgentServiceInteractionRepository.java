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
package io.agentcore.managed.server.agentservice.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentServiceInteractionRepository
        extends JpaRepository<AgentServiceInteractionEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from AgentServiceInteractionEntity i where i.id = :id")
    Optional<AgentServiceInteractionEntity> lockById(@Param("id") String id);

    long countBySessionIdAndKindInAndNativeStatusIn(
            String session, List<String> kinds, List<String> statuses);

    long countByTurnIdAndKindAndNativeStatusIn(String turn, String kind, List<String> statuses);

    Optional<AgentServiceInteractionEntity>
            findFirstByTurnIdAndKindAndNativeStatusInOrderByCreatedAtDesc(
                    String turn, String kind, List<String> statuses);

    @Query(
            "select i.id from AgentServiceInteractionEntity i where i.deliveryStatus = 'pending'"
                + " and i.nextAttemptAt <= :now and i.claimUntil <= :now order by case when i.kind"
                + " = 'cancel' then 0 else 1 end, i.nextAttemptAt, i.createdAt, i.id")
    List<String> findDue(@Param("now") long now, Pageable page);
}
