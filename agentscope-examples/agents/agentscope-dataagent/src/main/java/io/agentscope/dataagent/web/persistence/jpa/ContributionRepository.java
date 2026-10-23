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
package io.agentscope.dataagent.web.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/** JPA repository for {@link ContributionEntity}. */
public interface ContributionRepository extends JpaRepository<ContributionEntity, Long> {

    List<ContributionEntity> findAllByStatusOrderByCreatedAtDesc(@Param("status") String status);

    List<ContributionEntity> findAllBySourceUserIdOrderByCreatedAtDesc(
            @Param("sourceUserId") String sourceUserId);
}
