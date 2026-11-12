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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** Agent Service-owned registry and bootstrap outbox. Execution secrets are removed after acknowledgement. */
@Entity
@Table(
        name = "builder_portal_session",
        indexes = {
            @Index(
                    name = "ix_portal_provisioning",
                    columnList = "state,next_attempt_at,claim_until"),
            @Index(
                    name = "ix_portal_session_scope",
                    columnList = "tenant,namespace,owner_id,created_at,id"),
            @Index(name = "ix_portal_child_root", columnList = "root_session_id,id")
        },
        uniqueConstraints =
                @UniqueConstraint(name = "uq_portal_bootstrap_command", columnNames = "command_id"))
public class AgentServiceSessionEntity {
    @Id
    @Column(length = 256)
    public String id;

    @Column(nullable = false, length = 128)
    public String tenant;

    @Column(nullable = false, length = 128)
    public String namespace;

    @Column(name = "owner_id", nullable = false, length = 128)
    public String ownerId;

    @Column(name = "root_session_id", length = 256)
    public String rootSessionId;

    @Column(name = "parent_session_id", length = 256)
    public String parentSessionId;

    @Column(name = "application_id", length = 256)
    public String applicationId;

    @Column(name = "runtime_kind", nullable = false, length = 32)
    public String runtimeKind;

    @Column(name = "command_id", nullable = false, length = 256)
    public String commandId;

    @Column(nullable = false, length = 256)
    public String revision;

    @Column(nullable = false, length = 64)
    public String digest;

    @Column(nullable = false, length = 32)
    public String state;

    @Column(nullable = false, length = 32)
    public String lifecycle;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String policy;

    @Column(name = "public_view", nullable = false, columnDefinition = "TEXT")
    public String publicView;

    @Column(length = 500)
    public String title;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT")
    public String metadataJson = "{}";

    @Column(name = "creation_request_digest", length = 64)
    public String creationRequestDigest;

    @Column(name = "bootstrap_payload", columnDefinition = "TEXT")
    public String bootstrapPayload;

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

    @Column(name = "next_turn_sequence", nullable = false)
    public long nextTurnSequence;

    @Column(name = "next_event_sequence", nullable = false)
    public long nextEventSequence;

    @Column(name = "policy_version", nullable = false)
    public long policyVersion = 1;

    @Column(name = "budget_limits", nullable = false, columnDefinition = "TEXT")
    public String budgetLimits = "{}";

    @Column(name = "budget_revision", nullable = false)
    public long budgetRevision = 1;

    @Column(name = "usage_summary", nullable = false, columnDefinition = "TEXT")
    public String usageSummary = "{\"scope\":\"session_only\",\"model_calls\":0,\"totals\":{}}";

    @Column(name = "policy_id", length = 64)
    public String policyId;

    @Column(name = "lifecycle_revision", nullable = false)
    public long lifecycleRevision = 1;

    @Version public long version;
}
