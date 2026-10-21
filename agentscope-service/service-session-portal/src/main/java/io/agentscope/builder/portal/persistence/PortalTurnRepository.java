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

public interface PortalTurnRepository extends JpaRepository<PortalTurnEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PortalTurnEntity t where t.id = :id")
    Optional<PortalTurnEntity> lockById(@Param("id") String id);

    @Query(
            "select t.id from PortalTurnEntity t where t.deliveryStatus = 'pending' and"
                + " t.nextAttemptAt <= :now and t.claimUntil <= :now and not exists (select"
                + " older.id from PortalTurnEntity older where older.sessionId = t.sessionId and"
                + " older.admissionSequence < t.admissionSequence and older.deliveryStatus in"
                + " ('pending', 'blocked')) order by t.nextAttemptAt, t.createdAt, t.id")
    List<String> findDue(@Param("now") long now, Pageable page);

    @Query(
            "select count(t) from PortalTurnEntity t where t.sessionId=:session and"
                    + " (t.reservationState='active' or t.status not in"
                    + " ('completed','cancelled','failed','interrupted'))")
    long countUnsettled(@Param("session") String session);

    @Query(
            "select t from PortalTurnEntity t where t.sessionId=:session and"
                + " (t.reservationState='active' or t.status not in"
                + " ('completed','cancelled','failed','interrupted')) order by t.admissionSequence")
    List<PortalTurnEntity> unsettled(@Param("session") String session, Pageable page);

    long countBySessionIdAndStatus(String sessionId, String status);

    long countBySessionIdAndStatusNotIn(String sessionId, List<String> statuses);

    boolean existsByIdAndSessionId(String id, String sessionId);

    List<PortalTurnEntity>
            findBySessionIdAndAdmissionSequenceGreaterThanOrderByAdmissionSequenceAsc(
                    String sessionId, long after, Pageable page);

    @Query(
            "select t from PortalTurnEntity t where t.sessionId=:session and"
                    + " (:status is null or t.status=:status) and t.admissionSequence < :before"
                    + " order by t.admissionSequence desc")
    List<PortalTurnEntity> newest(
            @Param("session") String session,
            @Param("status") String status,
            @Param("before") long before,
            Pageable page);
}
