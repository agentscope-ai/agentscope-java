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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentscope.harness.agent.tools.ToolsConfig;

/** Sealed execution-only material. Model configuration and native checkpoint bytes are excluded. */
public record ManagedToolTarget(SessionResolveResult resolved, ToolsConfig tools) {
    /** Pure configuration validation; reserving the physical instance remains a Tool Worker operation. */
    public static String existingSandboxId(SessionResolveResult resolved) {
        var environment = resolved.environment();
        if (environment == null || !"self_hosted".equals(environment.type())) return null;
        Object sandbox =
                environment.config() == null ? null : environment.config().get("sandboxId");
        if (resolved.session() != null && resolved.session().resources() != null)
            for (var resource : resolved.session().resources())
                if ("sandbox_binding".equals(resource.get("type")))
                    sandbox = resource.get("sandboxId");
        if (!(sandbox instanceof String id) || id.isBlank())
            throw new ManagedStateException(
                    "sandbox_binding_required",
                    "self_hosted execution requires an existing sandbox ID");
        return id;
    }

    public static String existingSandboxIdentity(SessionResolveResult resolved) {
        String id = existingSandboxId(resolved);
        if (id == null) return null;
        var config = resolved.environment().config();
        String endpoint =
                String.valueOf(
                        config == null
                                ? "e2b-default"
                                : config.getOrDefault(
                                        "apiBaseUrl",
                                        config.getOrDefault("domain", "e2b-default")));
        return ManagedScope.fingerprint(endpoint.replaceAll("/+$", ""), id);
    }
}
