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

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link AgentEntity}. */
public interface AgentEntityRepository extends JpaRepository<AgentEntity, Long> {

    /** All agent definitions owned by {@code ownerId}, oldest first. */
    List<AgentEntity> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    /** Single definition lookup by the {@code (ownerId, agentId)} pair. */
    Optional<AgentEntity> findByOwnerIdAndAgentId(String ownerId, String agentId);
}
