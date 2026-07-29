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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link AgentStateEntity}. */
public interface AgentStateEntityRepository extends JpaRepository<AgentStateEntity, Long> {

    Optional<AgentStateEntity> findByUserIdAndSessionIdAndStateKey(
            String userId, String sessionId, String stateKey);

    boolean existsByUserIdAndSessionId(String userId, String sessionId);

    @Modifying
    @Query(
            "delete from AgentStateEntity e where e.userId = :userId and e.sessionId ="
                    + " :sessionId")
    void deleteByUserIdAndSessionId(
            @Param("userId") String userId, @Param("sessionId") String sessionId);

    @Modifying
    @Query(
            "delete from AgentStateEntity e where e.userId = :userId and e.sessionId ="
                    + " :sessionId and e.stateKey = :stateKey")
    void deleteByUserIdAndSessionIdAndStateKey(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("stateKey") String stateKey);

    @Query(
            "select distinct e.sessionId from AgentStateEntity e where e.userId = :userId order"
                    + " by e.sessionId")
    List<String> findDistinctSessionIdsByUserId(@Param("userId") String userId);
}
