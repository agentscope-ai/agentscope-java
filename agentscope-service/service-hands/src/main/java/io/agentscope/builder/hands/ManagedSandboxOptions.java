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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.sandbox.e2b.E2bCodec;
import io.agentscope.extensions.sandbox.e2b.E2bSandboxClientOptions;

/** Provider-specific options shared by platform Hands and outbound Workers. */
public final class ManagedSandboxOptions {
    private ManagedSandboxOptions() {}

    public static E2bSandboxClientOptions from(JsonNode config) {
        var options = new E2bSandboxClientOptions();
        options.setApiKey(config.path("apiKey").asText());
        options.setTemplateId(config.path("templateId").asText("base"));
        options.setWorkspaceRoot(config.path("workspaceRoot").asText("/home/user"));
        options.setSandboxTimeoutSeconds(
                Math.max(120, Math.min(3600, config.path("sandboxTimeoutSeconds").asInt(300))));
        if (config.hasNonNull("apiBaseUrl"))
            options.setApiBaseUrl(config.path("apiBaseUrl").asText());
        if (config.hasNonNull("envdBaseUrl"))
            options.setEnvdBaseUrl(config.path("envdBaseUrl").asText());
        if (config.hasNonNull("domain")) options.setDomain(config.path("domain").asText());
        options.setCodec(E2bCodec.valueOf(config.path("codec").asText("PROTO")));
        options.setMaxRetries(0);
        options.setConnectTimeoutSeconds(5);
        options.setReadTimeoutSeconds(config.path("readTimeoutSeconds").asInt(30));
        options.setRunUser(config.path("runUser").asText("user"));
        options.setMaxCapturedBytes(64 * 1024 * 1024);
        options.setRecreateOnConnectFailure(false);
        return options;
    }
}
