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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** Public Turn, its reservation, and its durable execution-command outbox commit together. */
@Entity
@Table(
        name = "builder_portal_turn",
        indexes = {
            @Index(
                    name = "ix_portal_turn_delivery",
                    columnList = "delivery_status,next_attempt_at,claim_until"),
            @Index(name = "ix_portal_turn_session", columnList = "session_id,admission_sequence"),
            @Index(name = "ix_portal_turn_pending", columnList = "session_id,status")
        },
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_portal_turn_order",
                        columnNames = {"session_id", "admission_sequence"}))
public class PortalTurnEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(name = "quota_id", nullable = false, length = 64)
    public String quotaId;

    @Column(name = "input_digest", nullable = false, length = 64)
    public String inputDigest;

    @Column(name = "admission_sequence", nullable = false)
    public long admissionSequence;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "reservation_state", nullable = false, length = 32)
    public String reservationState;

    @Column(name = "queued_reservation", nullable = false)
    public boolean queuedReservation;

    @Column(name = "release_receipt", length = 256)
    public String releaseReceipt;

    @Column(name = "permission_until", nullable = false)
    public long permissionUntil;

    @Column(name = "execution_generation", nullable = false)
    public long executionGeneration;

    @Column(name = "activation_command_id", nullable = false, length = 64)
    public String activationCommandId;

    @Column(name = "execution_fence", length = 256)
    public String executionFence;

    @Column(name = "grant_until", nullable = false)
    public long grantUntil;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String command;

    @Column(name = "delivery_status", nullable = false, length = 32)
    public String deliveryStatus;

    @Column(name = "claim_token", length = 64)
    public String claimToken;

    @Column(name = "claim_until", nullable = false)
    public long claimUntil;

    @Column(name = "next_attempt_at", nullable = false)
    public long nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "failure_code", length = 64)
    public String failureCode;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;

    @Column(name = "usage_summary", nullable = false, columnDefinition = "TEXT")
    public String usageSummary = "{\"scope\":\"turn_only\",\"model_calls\":0,\"totals\":{}}";

    @Version public long version;
}
