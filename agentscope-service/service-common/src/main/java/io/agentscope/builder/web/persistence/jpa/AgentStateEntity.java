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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One row of persisted {@link io.agentscope.core.state.AgentStateStore} data.
 *
 * <p>{@code list_kind=false} stores a single JSON object; {@code list_kind=true} stores a JSON
 * array (full list replacement).
 */
@Entity
@Table(
        name = "builder_agent_state",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_builder_agent_state_slot",
                        columnNames = {"user_id", "session_id", "state_key"}),
        indexes = {
            @Index(name = "ix_builder_agent_state_user", columnList = "user_id"),
            @Index(name = "ix_builder_agent_state_user_session", columnList = "user_id,session_id")
        })
public class AgentStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(name = "state_key", length = 255, nullable = false)
    private String stateKey;

    /** When true, {@link #stateData} is a JSON array; otherwise a single JSON object. */
    @Column(name = "list_kind", nullable = false)
    private boolean listKind;

    @Lob
    @Column(name = "state_data", nullable = false)
    private String stateData;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getStateKey() {
        return stateKey;
    }

    public void setStateKey(String stateKey) {
        this.stateKey = stateKey;
    }

    public boolean isListKind() {
        return listKind;
    }

    public void setListKind(boolean listKind) {
        this.listKind = listKind;
    }

    public String getStateData() {
        return stateData;
    }

    public void setStateData(String stateData) {
        this.stateData = stateData;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
