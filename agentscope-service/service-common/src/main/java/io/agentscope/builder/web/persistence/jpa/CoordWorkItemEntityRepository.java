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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordWorkItemEntityRepository extends JpaRepository<CoordWorkItemEntity, Long> {

    Optional<CoordWorkItemEntity> findByLeaseId(String leaseId);

    Optional<CoordWorkItemEntity> findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(
            String sessionId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select e from CoordWorkItemEntity e where e.environmentId = :environmentId and"
                    + " (e.status = :queued or (e.status in :reclaimable and e.updatedAt <"
                    + " :staleBefore)) order by e.createdAt asc")
    List<CoordWorkItemEntity> findClaimableForPoll(
            @Param("environmentId") String environmentId,
            @Param("queued") String queued,
            @Param("reclaimable") List<String> reclaimable,
            @Param("staleBefore") long staleBefore);

    List<CoordWorkItemEntity> findByEnvironmentIdOrderByCreatedAtAsc(String environmentId);

    List<CoordWorkItemEntity> findByEnvironmentIdAndStatusOrderByCreatedAtAsc(
            String environmentId, String status);

    long countByStatus(String status);

    long countByEnvironmentIdAndStatus(String environmentId, String status);

    @Query(
            "select min(e.createdAt) from CoordWorkItemEntity e where e.environmentId ="
                    + " :environmentId and e.status = :status")
    Optional<Long> findOldestCreatedAtByEnvironmentIdAndStatus(
            @Param("environmentId") String environmentId, @Param("status") String status);
}
