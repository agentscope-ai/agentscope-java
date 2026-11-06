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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Immutable outbound report plus renewable delivery ownership. Never changes Attempt on retry. */
@Entity
@Table(
        name = "builder_session_event_outbox",
        indexes = {
            @Index(
                    name = "ix_event_outbox_delivery",
                    columnList = "delivered_at,next_attempt_at,lease_until"),
            @Index(name = "ix_event_outbox_session", columnList = "session_id,event_seq"),
            @Index(
                    name = "ix_event_outbox_attempt",
                    columnList = "session_id,attempt_id,delivered_at")
        })
public class SessionEventOutboxEntity {
    @Id
    @Column(name = "event_id", length = 128)
    public String eventId;

    @Column(name = "session_id", nullable = false, length = 64)
    public String sessionId;

    @Column(name = "attempt_id", nullable = false, length = 64)
    public String attemptId;

    @Column(name = "event_seq", nullable = false)
    public long eventSeq;

    @Column(name = "report_json", nullable = false, columnDefinition = "TEXT")
    public String reportJson;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "delivered_at", nullable = false)
    public long deliveredAt;

    @Column(name = "next_attempt_at", nullable = false)
    public long nextAttemptAt;

    @Column(name = "lease_until", nullable = false)
    public long leaseUntil;

    @Column(name = "worker_id", length = 64)
    public String workerId;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "last_error", length = 1024)
    public String lastError;

    @Version public long version;
}
