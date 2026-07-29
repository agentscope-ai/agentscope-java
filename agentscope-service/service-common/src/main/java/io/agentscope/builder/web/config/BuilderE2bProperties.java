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
package io.agentscope.builder.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global defaults for Managed Environment {@code type=sandbox} (E2B cloud). Per-environment
 * {@code config} overrides these values. Set {@code BUILDER_E2B_API_KEY} or {@code E2B_API_KEY}
 * when not embedding the key in environment config.
 */
@ConfigurationProperties(prefix = "builder.e2b")
public class BuilderE2bProperties {

    /** E2B API key (preferred global source). */
    private String apiKey = "";

    /** Default E2B template id when environment config omits {@code templateId}. */
    private String templateId = "base";

    /** Default workspace root inside the E2B sandbox. */
    private String workspaceRoot = "/home/user";

    /** Default sandbox lifetime / idle timeout seconds. */
    private int sandboxTimeoutSeconds = 300;

    /** Optional API base URL override (default from E2B extension). */
    private String apiBaseUrl = "";

    /** Optional domain override (default from E2B extension). */
    private String domain = "";

    /** {@code TAR} or {@code NATIVE_SNAPSHOT}. */
    private String persistenceMode = "TAR";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(String persistenceMode) {
        this.persistenceMode = persistenceMode;
    }
}
