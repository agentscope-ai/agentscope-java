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

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentServiceResourceRepository
        extends JpaRepository<AgentServiceResourceEntity, String> {
    @Query(
            "select r from AgentServiceResourceEntity r where r.sessionId = :session and r.kind ="
                + " :kind and (:turn is null or r.turnId = :turn) and r.resourceKey > :after and"
                + " r.validFrom <= :through order by r.resourceKey")
    List<AgentServiceResourceEntity> page(
            @Param("session") String session,
            @Param("kind") String kind,
            @Param("turn") String turn,
            @Param("after") String after,
            @Param("through") long through,
            Pageable page);
}
