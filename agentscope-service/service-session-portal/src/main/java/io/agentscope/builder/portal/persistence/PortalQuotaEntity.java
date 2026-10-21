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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Shared short-transaction counter; public backends use this same reservation authority. */
@Entity
@Table(name = "builder_portal_quota")
public class PortalQuotaEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 128)
    public String tenant;

    @Column(nullable = false, length = 128)
    public String namespace;

    @Column(name = "application_id", length = 256)
    public String applicationId;

    @Column(name = "active_count", nullable = false)
    public long activeCount;

    @Column(name = "queued_count", nullable = false)
    public long queuedCount;

    @Column(name = "max_concurrent", nullable = false)
    public long maxConcurrent;

    @Column(name = "token_budget", nullable = false)
    public long tokenBudget;

    @Column(name = "tokens_used", nullable = false)
    public long tokensUsed;

    @Column(name = "configuration_version", nullable = false)
    public long configurationVersion;

    @Column(name = "window_start", nullable = false)
    public long windowStart;

    @Column(name = "window_count", nullable = false)
    public long windowCount;

    @Version public long version;
}
