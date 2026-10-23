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
package io.agentscope.harness.agent.tool;

/**
 * Configuration for {@link SkillManageTool}.
 *
 * <p>Default values are tuned for enterprise production deployment: new skills land in a draft
 * subdirectory (not the live skills root), static security scanning is on, and the agent cannot
 * silently overwrite an existing skill via {@code create}.
 *
 * <p>For personal-assistant / experimental use the recommended override is
 * {@code SkillManageConfig.builder().autoPromote(true).build()} — agent writes go straight to the
 * live skills root, becoming visible on the next reasoning turn.
 */
public final class SkillManageConfig {

    /** Default subdirectory under workspace for staging drafts. */
    public static final String DEFAULT_DRAFTS_DIR = "skills/_drafts";

    /** Default subdirectory under workspace for promoted skills. */
    public static final String DEFAULT_MAIN_DIR = "skills";

    private final boolean autoPromote;
    private final boolean securityScan;
    private final String draftsDir;
    private final String mainDir;

    private SkillManageConfig(Builder b) {
        this.autoPromote = b.autoPromote;
        this.securityScan = b.securityScan;
        this.draftsDir = b.draftsDir;
        this.mainDir = b.mainDir;
    }

    /** Skip the draft staging path and write directly to the live skills root. */
    public boolean autoPromote() {
        return autoPromote;
    }

    /** Run {@code SkillSecurityScanner} after every write. */
    public boolean securityScan() {
        return securityScan;
    }

    /** Workspace-relative directory where drafts land. */
    public String draftsDir() {
        return draftsDir;
    }

    /** Workspace-relative directory where promoted skills live. */
    public String mainDir() {
        return mainDir;
    }

    public static SkillManageConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean autoPromote = false;
        private boolean securityScan = true;
        private String draftsDir = DEFAULT_DRAFTS_DIR;
        private String mainDir = DEFAULT_MAIN_DIR;

        private Builder() {}

        public Builder autoPromote(boolean autoPromote) {
            this.autoPromote = autoPromote;
            return this;
        }

        public Builder securityScan(boolean securityScan) {
            this.securityScan = securityScan;
            return this;
        }

        public Builder draftsDir(String draftsDir) {
            if (draftsDir != null && !draftsDir.isBlank()) {
                this.draftsDir = draftsDir;
            }
            return this;
        }

        public Builder mainDir(String mainDir) {
            if (mainDir != null && !mainDir.isBlank()) {
                this.mainDir = mainDir;
            }
            return this;
        }

        public SkillManageConfig build() {
            return new SkillManageConfig(this);
        }
    }
}
