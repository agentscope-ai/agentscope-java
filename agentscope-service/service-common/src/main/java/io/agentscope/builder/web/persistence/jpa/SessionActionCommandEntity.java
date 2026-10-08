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

@Entity
@Table(
        name = "builder_session_action_command",
        indexes = {
            @Index(name = "ix_action_command_delivery", columnList = "delivery_status,created_at"),
            @Index(name = "ix_action_command_turn", columnList = "turn_id,created_at")
        })
public class SessionActionCommandEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 64)
    public String turnId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String requestJson;

    @Column(nullable = false, length = 24)
    public String deliveryStatus = "applied";

    @Column(nullable = false)
    public long createdAt;

    public SessionActionCommandEntity() {}
}
