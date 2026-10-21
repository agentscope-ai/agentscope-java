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
import jakarta.persistence.Version;

/** One ordered event cursor and at most one pending delivery per subscription. */
@Entity
@Table(
        name = "builder_portal_webhook",
        indexes = {
            @Index(name = "ix_portal_webhook_session", columnList = "session_id,id"),
            @Index(name = "ix_portal_webhook_limit", columnList = "session_id,state,id"),
            @Index(
                    name = "ix_portal_webhook_due",
                    columnList = "state,next_attempt_at,claim_until,id")
        })
public class PortalWebhookEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(nullable = false, length = 4096)
    public String url;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String actor;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String secret;

    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    public String eventTypes;

    @Column(name = "input_digest", nullable = false, length = 64)
    public String inputDigest;

    @Column(nullable = false, length = 32)
    public String state;

    @Column(name = "cursor_seq", nullable = false)
    public long cursorSeq;

    @Column(name = "pending_event", length = 256)
    public String pendingEvent;

    @Column(name = "pending_seq", nullable = false)
    public long pendingSeq;

    @Column(name = "delivery_id", length = 64)
    public String deliveryId;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "claim_token", length = 64)
    public String claimToken;

    @Column(name = "claim_fence", nullable = false)
    public long claimFence;

    @Column(name = "claim_until", nullable = false)
    public long claimUntil;

    @Column(name = "next_attempt_at", nullable = false)
    public long nextAttemptAt;

    @Column(name = "failure_code", length = 64)
    public String failureCode;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;

    @Version public long version;
}
