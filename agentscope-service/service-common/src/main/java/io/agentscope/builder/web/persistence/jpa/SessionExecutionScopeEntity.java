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
import jakarta.persistence.Version;

/** Admission precedes agent construction; run identity is bound before its subscription. */
@Entity
@Table(
        name = "builder_session_execution_scope",
        indexes = {
            @Index(name = "ix_execution_scope_run", columnList = "run_id", unique = true),
            @Index(name = "ix_execution_scope_attempt", columnList = "session_id,attempt_id")
        })
public class SessionExecutionScopeEntity {
    @Id
    @Column(name = "admission_id", length = 128)
    public String admissionId;

    @Column(name = "session_id", nullable = false, length = 64)
    public String sessionId;

    @Column(name = "attempt_id", length = 64)
    public String attemptId;

    @Column(name = "run_id", length = 64)
    public String runId;

    @Column(name = "scope_json", nullable = false, columnDefinition = "TEXT")
    public String scopeJson;

    @Column(name = "lease_until", nullable = false)
    public long leaseUntil;

    @Column(name = "finished", nullable = false)
    public boolean finished;

    @Column(name = "native_start_seq", nullable = false)
    public long nativeStartSeq;

    @Version public long version;
}
