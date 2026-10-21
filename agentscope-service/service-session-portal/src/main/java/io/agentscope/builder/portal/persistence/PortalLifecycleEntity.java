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

/** Desired lifecycle and its durable runtime courier; rows survive public deletion. */
@Entity
@Table(
        name = "builder_portal_lifecycle",
        indexes =
                @Index(
                        name = "ix_portal_lifecycle_due",
                        columnList = "status,next_attempt_at,claim_until"))
public class PortalLifecycleEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(nullable = false, length = 32)
    public String lifecycle;

    @Column(nullable = false)
    public long revision;

    @Column(name = "input_digest", nullable = false, length = 64)
    public String inputDigest;

    @Column(name = "payload_digest", nullable = false, length = 64)
    public String payloadDigest;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String payload;

    @Column(nullable = false, length = 32)
    public String status = "pending";

    @Column(name = "claim_token", length = 64)
    public String claimToken;

    @Column(name = "claim_until", nullable = false)
    public long claimUntil;

    @Column(name = "next_attempt_at", nullable = false)
    public long nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;

    @Version public long version;
}
