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

public interface AgentServicePolicyRepository
        extends JpaRepository<AgentServicePolicyEntity, String> {
    /** Frequent authorization reads exclude credential and resource configuration payloads. */
    interface PolicyView {
        String getId();

        String getTenant();

        String getNamespace();

        String getPolicy();

        long getPolicyVersion();

        long getValidUntil();
    }

    @Query(
            "select p.id as id, p.tenant as tenant, p.namespace as namespace, p.policy as policy,"
                    + " p.policyVersion as policyVersion, p.validUntil as validUntil from"
                    + " AgentServicePolicyEntity p where p.id = :id")
    Optional<PolicyView> readAuthorization(@Param("id") String id);

    @Query(
            "select p.id as id, p.tenant as tenant, p.namespace as namespace, p.policy as policy,"
                    + " p.policyVersion as policyVersion, p.validUntil as validUntil from"
                    + " AgentServicePolicyEntity p where p.id in :ids")
    List<PolicyView> readAuthorizations(@Param("ids") List<String> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AgentServicePolicyEntity p where p.id = :id")
    Optional<AgentServicePolicyEntity> lockById(@Param("id") String id);

    @Query(
            "select p.id from AgentServicePolicyEntity p where p.id > :after and (:tenant = '' or"
                    + " p.tenant = :tenant) and (:namespace = '' or p.namespace = :namespace) and"
                    + " (:application = '' or p.applicationId = :application) order by p.id")
    List<String> matching(
            @Param("tenant") String tenant,
            @Param("namespace") String namespace,
            @Param("application") String application,
            @Param("after") String after,
            Pageable page);

    @Query(
            "select p.id from AgentServicePolicyEntity p where p.nextAttemptAt <= :now and"
                + " p.claimUntil <= :now and exists (select s.id from AgentServiceSessionEntity s"
                + " where s.policyId = p.id and s.state in ('created','ready','checkpointing') and"
                + " s.lifecycle <> 'deleted') order by p.nextAttemptAt, p.id")
    List<String> findDue(@Param("now") long now, Pageable page);
}
