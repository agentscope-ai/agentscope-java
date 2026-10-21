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

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortalSessionRepository extends JpaRepository<PortalSessionEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PortalSessionEntity s where s.id = :id")
    Optional<PortalSessionEntity> lockById(@Param("id") String id);

    Optional<PortalSessionEntity> findFirstByPolicyIdAndStateAndLifecycleNotOrderByIdAsc(
            String policyId, String state, String lifecycle);

    @Query(
            "select s from PortalSessionEntity s where s.policyId=:policy and s.state in"
                + " ('created','ready','checkpointing') and s.lifecycle<>'deleted' order by s.id")
    List<PortalSessionEntity> policyRepresentative(@Param("policy") String policy, Pageable page);

    @Query(
            "select s.id from PortalSessionEntity s where s.state = 'provisioning' and"
                    + " s.nextAttemptAt <= :now and s.claimUntil <= :now order by s.nextAttemptAt,"
                    + " s.id")
    List<String> findDue(@Param("now") long now, Pageable page);
}
