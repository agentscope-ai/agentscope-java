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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Historical authorization survives newer activations so committed fact backlogs remain valid. */
@Entity
@Table(
        name = "builder_portal_execution_grant",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_portal_execution_generation",
                        columnNames = {"turn_id", "generation"}))
public class AgentServiceExecutionGrantEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "turn_id", nullable = false, length = 64)
    public String turnId;

    @Column(nullable = false)
    public long generation;

    @Column(name = "activation_command_id", nullable = false, length = 64)
    public String activationCommandId;

    @Column(nullable = false, length = 256)
    public String fence;

    @Column(name = "valid_until", nullable = false)
    public long validUntil;
}
