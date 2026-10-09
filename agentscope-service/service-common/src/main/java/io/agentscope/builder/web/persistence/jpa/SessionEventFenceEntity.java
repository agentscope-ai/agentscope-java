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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Serializes admission, event enqueue and terminal sealing for one immutable CP Attempt. */
@Entity
@Table(name = "builder_session_event_fence")
public class SessionEventFenceEntity {
    @Id
    @Column(name = "attempt_id", length = 64)
    public String attemptId;

    @Column(name = "session_id", nullable = false, length = 64)
    public String sessionId;

    @Column(nullable = false)
    public boolean closing;

    @Column(nullable = false)
    public boolean sealed;

    @Version public long version;
}
