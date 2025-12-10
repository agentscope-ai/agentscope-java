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
public class RemeAddRequest {

    /** Workspace identifier for memory organization. */
    @JsonProperty("workspace_id")
    private String workspaceId;

    /** List of trajectories (conversation sequences) to process. */
    private List<ReMeTrajectory> trajectories;

    /** Default constructor for Jackson. */
    public RemeAddRequest() {}

    /**
     * Creates a new RemeAddRequest with specified workspace ID and trajectories.
     *
     * @param workspaceId The workspace identifier
     * @param trajectories The list of trajectories
     */
    public RemeAddRequest(String workspaceId, List<ReMeTrajectory> trajectories) {
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
     * Creates a new builder for RemeAddRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for RemeAddRequest. */
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

        public RemeAddRequest build() {
            return new RemeAddRequest(workspaceId, trajectories);
        }
    }
}
