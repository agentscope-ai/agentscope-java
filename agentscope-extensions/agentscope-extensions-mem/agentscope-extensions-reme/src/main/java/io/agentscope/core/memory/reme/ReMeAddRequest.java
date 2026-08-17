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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request object for adding memories to ReMe API.
 *
 * <p>This request is sent to the ReMe API's {@code POST /summary_personal_memory} endpoint
 * to record new memories. ReMe will process the trajectories and extract memorable information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeAddRequest {

    /** Workspace identifier for memory organization. */
    @JsonProperty("workspace_id")
    private String workspaceId;

    /** List of trajectories (conversation sequences) to process. */
    private List<ReMeTrajectory> trajectories;

    /** Default constructor for Jackson. */
    public ReMeAddRequest() {}

    /**
     * Creates a new ReMeAddRequest with specified workspace ID and trajectories.
     *
     * @param workspaceId The workspace identifier
     * @param trajectories The list of trajectories
     */
    public ReMeAddRequest(String workspaceId, List<ReMeTrajectory> trajectories) {
        this.workspaceId = workspaceId;
        this.trajectories = trajectories;
    }

    // Getters and Setters

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public List<ReMeTrajectory> getTrajectories() {
        return trajectories;
    }

    public void setTrajectories(List<ReMeTrajectory> trajectories) {
        this.trajectories = trajectories;
    }

    /**
     * Creates a new builder for ReMeAddRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for ReMeAddRequest. */
    public static class Builder {
        private String workspaceId;
        private List<ReMeTrajectory> trajectories;

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder trajectories(List<ReMeTrajectory> trajectories) {
            this.trajectories = trajectories;
            return this;
        }

        public ReMeAddRequest build() {
            return new ReMeAddRequest(workspaceId, trajectories);
        }
    }
}
