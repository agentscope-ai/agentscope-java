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

public interface AgentServiceLifecycleRepository
        extends JpaRepository<AgentServiceLifecycleEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentServiceLifecycleEntity c where c.id=:id")
    Optional<AgentServiceLifecycleEntity> lockById(@Param("id") String id);

    @Query(
            "select c.id from AgentServiceLifecycleEntity c where c.status='pending' and"
                + " c.nextAttemptAt<=:now and c.claimUntil<=:now and not exists (select p.id from"
                + " AgentServiceLifecycleEntity p where p.sessionId=c.sessionId and"
                + " p.revision<c.revision and p.status='pending') order by"
                + " c.nextAttemptAt,c.createdAt,c.id")
    List<String> findDue(@Param("now") long now, Pageable page);
}
