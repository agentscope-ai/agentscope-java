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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortalSessionPermissionRepository
        extends JpaRepository<PortalSessionPermissionEntity, String> {
    boolean existsByPrincipalKindAndPrincipalIdAndValidUntilGreaterThan(
            String kind, String principal, long now);

    @Modifying
    @Query("delete from PortalSessionPermissionEntity p where p.sessionId = :session")
    void removeSession(@Param("session") String session);

    @Query(
            "select g.id from PortalPolicyEntity g where g.validUntil <= :now and exists (select"
                + " p.id from PortalSessionPermissionEntity p where p.policyId = g.id and"
                + " p.principalKind = :kind and p.principalId = :principal and p.policyVersion ="
                + " g.policyVersion and p.validUntil > :now) and exists (select s.id from"
                + " PortalSessionEntity s where s.policyId = g.id and s.state in"
                + " ('ready','checkpointing') and s.lifecycle <> 'deleted' and (:tenant is null or"
                + " s.tenant = :tenant) and (:namespace is null or s.namespace = :namespace) and"
                + " (:status = 'all' or s.lifecycle = :status) and (:application is null or"
                + " s.applicationId = :application))")
    List<String> expiredPolicies(
            @Param("kind") String kind,
            @Param("principal") String principal,
            @Param("tenant") String tenant,
            @Param("namespace") String namespace,
            @Param("status") String status,
            @Param("application") String application,
            @Param("now") long now,
            Pageable page);

    @Query(
            "select s from PortalSessionEntity s where s.state in ('ready','checkpointing') and"
                + " s.lifecycle <> 'deleted' and (:tenant is null or s.tenant = :tenant) and"
                + " (:namespace is null or s.namespace = :namespace) and (:status = 'all' or"
                + " s.lifecycle = :status) and (:application is null or s.applicationId ="
                + " :application) and ((:ascending = false and (s.createdAt < :beforeTime or"
                + " s.createdAt = :beforeTime and s.id < :beforeId)) or (:ascending = true and"
                + " (s.createdAt > :beforeTime or s.createdAt = :beforeTime and s.id > :beforeId)))"
                + " and exists (select p.id from PortalSessionPermissionEntity p where"
                + " p.principalKind = :kind and p.principalId = :principal and p.validUntil > :now"
                + " and ((s.policyId is null and p.sessionId = s.id and p.policyVersion ="
                + " s.policyVersion) or (s.policyId is not null and p.policyId = s.policyId and"
                + " exists (select g.id from PortalPolicyEntity g where g.id = s.policyId and"
                + " g.policyVersion = p.policyVersion and g.validUntil > :now)))) order by case"
                + " when :ascending = true then s.createdAt end asc, case when :ascending = true"
                + " then s.id end asc, case when :ascending = false then s.createdAt end desc, case"
                + " when :ascending = false then s.id end desc")
    List<PortalSessionEntity> page(
            @Param("kind") String kind,
            @Param("principal") String principal,
            @Param("tenant") String tenant,
            @Param("namespace") String namespace,
            @Param("status") String status,
            @Param("application") String application,
            @Param("now") long now,
            @Param("ascending") boolean ascending,
            @Param("beforeTime") long beforeTime,
            @Param("beforeId") String beforeId,
            Pageable page);
}
