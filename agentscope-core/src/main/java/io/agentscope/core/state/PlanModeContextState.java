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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * Persisted plan-mode flag for an agent.
 *
 * <p>Plan mode is a read-only "design first" phase: while {@link #planActive} is {@code true}, the
 * {@code PlanModeMiddleware} denies mutating tool calls so the agent can investigate and draft a
 * plan without changing anything. {@link #currentPlanFile} is the workspace-relative path of the
 * markdown blueprint the agent is editing (if any).
 *
 * <p>This lives inside {@link AgentState} (rather than in volatile in-process memory) precisely
 * because agentscope agents are distributed and resumable: a plan-mode session must survive process
 * restarts and hand-offs across nodes. Toggling the flag and persisting the {@link AgentState} is
 * therefore the canonical way to switch modes dynamically at runtime.
 */
@JsonPropertyOrder({"plan_active", "current_plan_file"})
public final class PlanModeContextState {

    private boolean planActive;
    private String currentPlanFile;

    public PlanModeContextState() {
        this(false, null);
    }

    @JsonCreator
    public PlanModeContextState(
            @JsonProperty("plan_active") boolean planActive,
            @JsonProperty("current_plan_file") String currentPlanFile) {
        this.planActive = planActive;
        this.currentPlanFile = currentPlanFile;
    }

    @JsonProperty("plan_active")
    public boolean isPlanActive() {
        return planActive;
    }

    public void setPlanActive(boolean planActive) {
        this.planActive = planActive;
    }

    @JsonProperty("current_plan_file")
    public String getCurrentPlanFile() {
        return currentPlanFile;
    }

    public void setCurrentPlanFile(String currentPlanFile) {
        this.currentPlanFile = currentPlanFile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanModeContextState other)) {
            return false;
        }
        return planActive == other.planActive
                && Objects.equals(currentPlanFile, other.currentPlanFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planActive, currentPlanFile);
    }

    @Override
    public String toString() {
        return "PlanModeContextState{planActive="
                + planActive
                + ", currentPlanFile="
                + currentPlanFile
                + '}';
    }
}
