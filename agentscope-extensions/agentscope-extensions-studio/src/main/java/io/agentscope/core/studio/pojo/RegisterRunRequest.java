/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for registering a run with Studio.
 *
 * <p>This is sent via POST /trpc/registerRun when initializing Studio integration.
 */
public class RegisterRunRequest {
    @JsonProperty("id")
    private final String id;

    @JsonProperty("project")
    private final String project;

    @JsonProperty("name")
    private final String name;

    @JsonProperty("timestamp")
    private final String timestamp;

    @JsonProperty("pid")
    private final long pid;

    @JsonProperty("status")
    private final String status;

    @JsonProperty("run_dir")
    private final String runDir;

    private RegisterRunRequest(Builder builder) {
        this.id = builder.id;
        this.project = builder.project;
        this.name = builder.name;
        this.timestamp = builder.timestamp;
        this.pid = builder.pid;
        this.status = builder.status;
        this.runDir = builder.runDir;
    }

    /**
     * Gets the unique identifier for this run.
     *
     * @return the run ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the project name for this run.
     *
     * @return the project name
     */
    public String getProject() {
        return project;
    }

    /**
     * Gets the run name.
     *
     * @return the run name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the timestamp when this run was created.
     *
     * @return the timestamp in ISO 8601 format
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the process ID of the running application.
     *
     * @return the process ID
     */
    public long getPid() {
        return pid;
    }

    /**
     * Gets the current status of the run.
     *
     * @return the status (e.g., "running", "completed", "failed")
     */
    public String getStatus() {
        return status;
    }

    /**
     * Gets the directory where run artifacts are stored.
     *
     * @return the run directory path
     */
    public String getRunDir() {
        return runDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String project;
        private String name;
        private String timestamp;
        private long pid;
        private String status = "running";
        private String runDir = "";

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder pid(long pid) {
            this.pid = pid;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder runDir(String runDir) {
            this.runDir = runDir;
            return this;
        }

        public RegisterRunRequest build() {
            return new RegisterRunRequest(this);
        }
    }
}
