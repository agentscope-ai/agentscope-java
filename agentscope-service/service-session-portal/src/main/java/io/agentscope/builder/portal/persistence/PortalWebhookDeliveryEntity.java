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

/** Bounded metadata receipt. Neither callback response bodies nor credentials are retained. */
@Entity
@Table(
        name = "builder_portal_webhook_delivery",
        indexes = @Index(name = "ix_portal_webhook_receipts", columnList = "webhook_id,id"))
public class PortalWebhookDeliveryEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "webhook_id", nullable = false, length = 64)
    public String webhookId;

    @Column(name = "event_id", nullable = false, length = 256)
    public String eventId;

    @Column(name = "event_seq", nullable = false)
    public long eventSeq;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "claim_fence", nullable = false)
    public long claimFence;

    @Column(name = "claim_token", nullable = false, length = 64)
    public String claimToken;

    @Column(name = "http_status", nullable = false)
    public int httpStatus;

    @Column(name = "error_code", nullable = false, length = 64)
    public String errorCode;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;
}
