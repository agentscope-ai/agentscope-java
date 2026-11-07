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

/** Primary-key lock and event-row reference for immutable event IDs. */
@Entity
@Table(
        name = "builder_session_event_identity",
        indexes = @Index(name = "ix_session_event_identity_session", columnList = "session_id"))
public class SessionEventIdentityEntity {
    @Id
    @Column(name = "event_id", length = 64)
    public String eventId;

    @Column(name = "session_id", length = 64)
    public String sessionId;

    /** Populated with the event in the same transaction that creates this identity. */
    @Column(name = "event_row_id")
    public Long eventRowId;
}
