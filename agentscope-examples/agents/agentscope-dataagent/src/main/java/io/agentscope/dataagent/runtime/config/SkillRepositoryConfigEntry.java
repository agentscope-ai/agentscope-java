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
package io.agentscope.dataagent.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Declarative skill repository settings for {@code agentscope.json} (and similar) configs.
 *
 * <p>Use {@code type: "filesystem"} with {@link #path} or {@code type: "git"} with {@link
 * #remoteUrl}. Git support requires {@code io.agentscope:agentscope-extensions-skill-git-repository}
 * on the classpath.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillRepositoryConfigEntry {

    /**
     * {@code filesystem} — load from a directory ({@link #path}, relative to bootstrap {@code cwd}).
     *
     * <p>{@code git} — clone / sync a remote repository ({@link #remoteUrl}, optional {@link
     * #branch}, {@link #localPath}, etc.).
     */
    @JsonProperty("type")
    private String type;

    /** Directory containing skill folders (each with {@code SKILL.md}). Used when {@code type} is {@code filesystem}. */
    @JsonProperty("path")
    private String path;

    @JsonProperty("remoteUrl")
    private String remoteUrl;

    @JsonProperty("branch")
    private String branch;

    /**
     * Local clone directory; when set, resolved relative to bootstrap {@code cwd}. Optional for
     * {@code git} (otherwise a temp directory is used by {@code GitSkillRepository}).
     */
    @JsonProperty("localPath")
    private String localPath;

    @JsonProperty("source")
    private String source;

    @JsonProperty("autoSync")
    private Boolean autoSync;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getAutoSync() {
        return autoSync;
    }

    public void setAutoSync(Boolean autoSync) {
        this.autoSync = autoSync;
    }
}
