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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PortalWebhookRepository extends JpaRepository<PortalWebhookEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PortalWebhookEntity w where w.id=:id")
    Optional<PortalWebhookEntity> lockById(@Param("id") String id);

    long countBySessionIdAndStateIn(String session, List<String> states);

    @Query("select w.sessionId from PortalWebhookEntity w where w.id=:id")
    Optional<String> sessionById(@Param("id") String id);

    List<PortalWebhookEntity> findBySessionIdAndIdGreaterThanOrderByIdAsc(
            String session, String after, Pageable page);

    @Query(
            "select w.id from PortalWebhookEntity w where w.state in ('active','idle') and"
                    + " w.nextAttemptAt<=:now and w.claimUntil<=:now order by w.nextAttemptAt,w.id")
    List<String> findDue(@Param("now") long now, Pageable page);

    @Transactional
    @Modifying
    @Query(
            "update PortalWebhookEntity w set"
                + " w.state='active',w.nextAttemptAt=:now,w.updatedAt=:now,w.version=w.version+1"
                + " where w.sessionId=:session and w.state='idle'")
    int wake(@Param("session") String session, @Param("now") long now);
}
