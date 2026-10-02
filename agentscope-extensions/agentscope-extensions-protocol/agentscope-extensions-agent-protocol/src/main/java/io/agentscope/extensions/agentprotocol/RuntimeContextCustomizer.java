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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook for shaping the {@link RuntimeContext} of an Agent Protocol task run.
 *
 * <p>The store always exposes the caller's {@code context.attributes} as one map under {@link
 * AgentProtocolConstants#RUNTIME_CONTEXT_ATTRIBUTES_KEY}, which cannot collide with framework keys.
 * Register customizer beans (ordered with {@code @Order}) to go further — promote selected
 * attributes to their own keys, translate them into typed values, or derive attributes of your own:
 *
 * <pre>{@code
 * @Bean
 * RuntimeContextCustomizer tenantContext() {
 *     return (request, builder) -> {
 *         String tenant = request.attributeString("tenant");
 *         if (tenant != null) {
 *             builder.put(TenantInfo.class, tenantService.load(tenant));
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Customizers run after the namespaced injection and in bean order, so a later one overrides an
 * earlier one. They are trusted: unlike {@link #flatten(String...)}, a hand-written customizer may
 * write any key, including framework-reserved ones.
 */
@FunctionalInterface
public interface RuntimeContextCustomizer {

    /**
     * Adjusts the context being built for {@code request}. Called once per run — on submit and
     * again on every resume.
     */
    void customize(AgentRequest request, RuntimeContext.Builder builder);

    /**
     * Customizer that copies the listed {@code context.attributes} entries to top-level runtime
     * context keys of the same name, for tools that read a plain key such as {@code
     * ctx.get("tenant")}.
     *
     * <p>Only the listed names are copied, and names the framework itself reads (see {@link
     * AgentProtocolConstants#isReservedRuntimeContextKey(String)}) are skipped with a warning, so a
     * caller cannot hijack routing keys like {@code agentId} by naming an attribute after one.
     */
    static RuntimeContextCustomizer flatten(String... keys) {
        Logger log = LoggerFactory.getLogger(RuntimeContextCustomizer.class);
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(keys));
        allowed.removeIf(
                key -> {
                    if (AgentProtocolConstants.isReservedRuntimeContextKey(key)) {
                        log.warn(
                                "Ignoring reserved runtime context key '{}' in"
                                        + " RuntimeContextCustomizer.flatten",
                                key);
                        return true;
                    }
                    return false;
                });
        return (request, builder) -> {
            Map<String, Object> attributes = request.attributes();
            for (String key : allowed) {
                Object value = attributes.get(key);
                if (value != null) {
                    builder.put(key, value);
                }
            }
        };
    }
}
