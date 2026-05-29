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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Persistent representation of one {@link io.agentscope.dataagent.web.share.AgentShareGrant}.
 *
 * <p>Owned by an {@link AgentEntity} via a {@code @ManyToOne} relationship with a hard foreign
 * key. {@code orphanRemoval=true} on the owning side ensures that removing a grant from the
 * agent's collection deletes the row.
 */
@Entity
@Table(
        name = "dataagent_agent_share",
        indexes = {@Index(name = "ix_agent_share_grantee", columnList = "grantee_type,grantee_id")})
public class AgentShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "agent_row_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_share_agent"))
    private AgentEntity agent;

    @Column(name = "grantee_type", length = 20, nullable = false)
    private String granteeType;

    @Column(name = "grantee_id", length = 128, nullable = false)
    private String granteeId;

    @Column(name = "tier", length = 20, nullable = false)
    private String tier;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    public AgentShareEntity() {}

    public AgentShareEntity(
            AgentEntity agent,
            String granteeType,
            String granteeId,
            String tier,
            long createdAt,
            String createdBy) {
        this.agent = agent;
        this.granteeType = granteeType;
        this.granteeId = granteeId;
        this.tier = tier;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public AgentEntity getAgent() {
        return agent;
    }

    public void setAgent(AgentEntity agent) {
        this.agent = agent;
    }

    public String getGranteeType() {
        return granteeType;
    }

    public void setGranteeType(String granteeType) {
        this.granteeType = granteeType;
    }

    public String getGranteeId() {
        return granteeId;
    }

    public void setGranteeId(String granteeId) {
        this.granteeId = granteeId;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
