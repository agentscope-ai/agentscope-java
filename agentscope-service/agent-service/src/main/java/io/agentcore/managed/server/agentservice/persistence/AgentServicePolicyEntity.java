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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One finite-lived resource/application/principal policy, shared by its Session references. */
@Entity
@Table(
        name = "builder_portal_policy",
        indexes =
                @Index(
                        name = "ix_portal_policy_refresh",
                        columnList = "next_attempt_at,claim_until"))
public class AgentServicePolicyEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 128)
    public String tenant;

    @Column(nullable = false, length = 128)
    public String namespace;

    @Column(name = "application_id", length = 256)
    public String applicationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String policy;

    @Column(name = "configuration_overlay", columnDefinition = "TEXT")
    public String configurationOverlay;

    @Column(name = "policy_version", nullable = false)
    public long policyVersion;

    @Column(name = "valid_until", nullable = false)
    public long validUntil;

    @Column(name = "next_attempt_at", nullable = false)
    public long nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "claim_token", length = 64)
    public String claimToken;

    @Column(name = "claim_until", nullable = false)
    public long claimUntil;

    @Column(name = "failure_code", length = 64)
    public String failureCode;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;

    @Version public long version;
}
