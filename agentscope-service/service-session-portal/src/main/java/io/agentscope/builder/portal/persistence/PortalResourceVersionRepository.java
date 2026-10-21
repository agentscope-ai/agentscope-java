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
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortalResourceVersionRepository
        extends JpaRepository<PortalResourceVersionEntity, String> {
    @Query(
            "select r from PortalResourceVersionEntity r where r.sessionId = :session and r.kind ="
                + " :kind and (:turn is null or r.turnId = :turn) and r.resourceKey > :after and"
                + " r.validFrom <= :through and r.validThrough > :through and r.expiresAt > :now"
                + " order by r.resourceKey")
    List<PortalResourceVersionEntity> page(
            @Param("session") String session,
            @Param("kind") String kind,
            @Param("turn") String turn,
            @Param("after") String after,
            @Param("through") long through,
            @Param("now") long now,
            Pageable page);

    @Query(
            "select r from PortalResourceVersionEntity r where r.sessionId = :session and r.kind ="
                + " :kind and (:turn is null or r.turnId = :turn) and r.resourceKey in :keys and"
                + " r.validFrom <= :through and r.validThrough > :through and r.expiresAt > :now")
    List<PortalResourceVersionEntity> atKeys(
            @Param("session") String session,
            @Param("kind") String kind,
            @Param("turn") String turn,
            @Param("keys") Set<String> keys,
            @Param("through") long through,
            @Param("now") long now);

    @Query(
            "select r.id from PortalResourceVersionEntity r where r.expiresAt <= :now order by"
                    + " r.expiresAt, r.id")
    List<String> expired(@Param("now") long now, Pageable page);
}
