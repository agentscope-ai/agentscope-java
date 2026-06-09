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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;

/** Options for {@link AgentRunSandboxClient}. */
public class AgentRunSandboxClientOptions extends SandboxClientOptions {

    /** Maximum number of instance-level OSS mounts AgentRun accepts per sandbox. */
    public static final int MAX_OSS_MOUNTS = 5;

    /** Allowed mount-dir prefixes for both NAS and OSS instance mounts. */
    public static final List<String> ALLOWED_MOUNT_PREFIXES = List.of("/home/", "/mnt/", "/data/");

    private OkHttpClient httpClient;
    private String apiKey;
    private String accountId;
    private String region;
    private String dataPlaneBaseUrl;
    private String templateName;
    private String mcpServerUrl;
    private String mcpEndpoint = "/mcp";
    private int sandboxIdleTimeoutSeconds = 1800;
    private AgentRunNasMountConfig nasConfig;
    private List<AgentRunOssMountConfig> ossMountConfigs = new ArrayList<>();
    private String workspaceRoot = AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT;
    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 120;
    private int maxRetries = 3;

    @Override
    public String getType() {
        return "agentrun";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new AgentRunSandboxClient(this, null);
    }

    /**
     * Validates option invariants that are independent of any active sandbox.
     *
     * @throws SandboxException.SandboxConfigurationException when a constraint is violated
     */
    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun API key is required (set AgentRunSandboxClientOptions#setApiKey)");
        }
        if (templateName == null || templateName.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun template name is required (set #setTemplateName)");
        }
        if (mcpServerUrl == null || mcpServerUrl.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun MCP server URL is required (set #setMcpServerUrl)");
        }
        if (nasConfig != null && nasConfig.getMountDir() != null) {
            requireAllowedMountDir("nasConfig.mountDir", nasConfig.getMountDir());
        }
        if (ossMountConfigs != null) {
            if (ossMountConfigs.size() > MAX_OSS_MOUNTS) {
                throw new SandboxException.SandboxConfigurationException(
                        "AgentRun supports at most "
                                + MAX_OSS_MOUNTS
                                + " OSS mounts per sandbox; got "
                                + ossMountConfigs.size());
            }
            for (int i = 0; i < ossMountConfigs.size(); i++) {
                AgentRunOssMountConfig m = ossMountConfigs.get(i);
                if (m == null) {
                    continue;
                }
                requireAllowedMountDir("ossMountConfigs[" + i + "].mountDir", m.getMountDir());
            }
        }
    }

    private static void requireAllowedMountDir(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    field + " must be set (under /home, /mnt or /data)");
        }
        for (String prefix : ALLOWED_MOUNT_PREFIXES) {
            if (value.startsWith(prefix)) {
                return;
            }
        }
        throw new SandboxException.SandboxConfigurationException(
                field + " must start with one of " + ALLOWED_MOUNT_PREFIXES + " but was: " + value);
    }

    /**
     * Returns the resolved data-plane base URL, deriving it from {@code accountId}/{@code region}
     * when not explicitly set.
     *
     * @return absolute data-plane base URL
     */
    public String getResolvedDataPlaneBaseUrl() {
        if (dataPlaneBaseUrl != null && !dataPlaneBaseUrl.isBlank()) {
            return stripTrailingSlash(dataPlaneBaseUrl);
        }
        if (accountId == null || accountId.isBlank() || region == null || region.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun requires accountId+region or an explicit dataPlaneBaseUrl");
        }
        return "https://" + accountId + ".agentrun-data." + region + ".aliyuncs.com";
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public AgentRunSandboxClientOptions setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }

    public String getApiKey() {
        return apiKey;
    }

    public AgentRunSandboxClientOptions setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public String getAccountId() {
        return accountId;
    }

    public AgentRunSandboxClientOptions setAccountId(String accountId) {
        this.accountId = accountId;
        return this;
    }

    public String getRegion() {
        return region;
    }

    public AgentRunSandboxClientOptions setRegion(String region) {
        this.region = region;
        return this;
    }

    public String getDataPlaneBaseUrl() {
        return dataPlaneBaseUrl;
    }

    public AgentRunSandboxClientOptions setDataPlaneBaseUrl(String dataPlaneBaseUrl) {
        this.dataPlaneBaseUrl = dataPlaneBaseUrl;
        return this;
    }

    public String getTemplateName() {
        return templateName;
    }

    public AgentRunSandboxClientOptions setTemplateName(String templateName) {
        this.templateName = templateName;
        return this;
    }

    public String getMcpServerUrl() {
        return mcpServerUrl;
    }

    public AgentRunSandboxClientOptions setMcpServerUrl(String mcpServerUrl) {
        this.mcpServerUrl = mcpServerUrl;
        return this;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public AgentRunSandboxClientOptions setMcpEndpoint(String mcpEndpoint) {
        this.mcpEndpoint = mcpEndpoint != null && !mcpEndpoint.isBlank() ? mcpEndpoint : "/mcp";
        return this;
    }

    public int getSandboxIdleTimeoutSeconds() {
        return sandboxIdleTimeoutSeconds;
    }

    public AgentRunSandboxClientOptions setSandboxIdleTimeoutSeconds(int seconds) {
        this.sandboxIdleTimeoutSeconds = seconds;
        return this;
    }

    public AgentRunNasMountConfig getNasConfig() {
        return nasConfig;
    }

    public AgentRunSandboxClientOptions setNasConfig(AgentRunNasMountConfig nasConfig) {
        this.nasConfig = nasConfig;
        return this;
    }

    public List<AgentRunOssMountConfig> getOssMountConfigs() {
        return ossMountConfigs;
    }

    public AgentRunSandboxClientOptions setOssMountConfigs(
            List<AgentRunOssMountConfig> ossMountConfigs) {
        this.ossMountConfigs = ossMountConfigs != null ? ossMountConfigs : new ArrayList<>();
        return this;
    }

    public AgentRunSandboxClientOptions addOssMount(AgentRunOssMountConfig mount) {
        if (mount != null) {
            this.ossMountConfigs.add(mount);
        }
        return this;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public AgentRunSandboxClientOptions setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot =
                workspaceRoot != null ? workspaceRoot : AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT;
        return this;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public AgentRunSandboxClientOptions setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        return this;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public AgentRunSandboxClientOptions setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
        return this;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public AgentRunSandboxClientOptions setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }
}
