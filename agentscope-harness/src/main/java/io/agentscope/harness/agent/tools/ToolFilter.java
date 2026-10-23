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
package io.agentscope.harness.agent.tools;

import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the {@link ToolsConfig#getAllow() allow} / {@link ToolsConfig#getDeny() deny} lists from
 * {@code workspace/tools.json} (or Managed Agents toolset export) against a {@link Toolkit}.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>When {@code allow} is non-empty, <em>catalogued</em> tools not listed are removed.
 *   <li>{@link HarnessPlatformTools} names always survive {@code allow} (Harness runtime must keep
 *       working). Explicit {@code deny} still removes them.
 *   <li>{@code deny} entries are always removed, regardless of {@code allow}.
 *   <li>When both are empty/absent the toolkit is left untouched.
 * </ul>
 *
 * <p>Names that don't correspond to a currently registered tool are logged at WARN and otherwise
 * ignored — typos in the workspace file should not abort the agent.
 */
public final class ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(ToolFilter.class);

    private ToolFilter() {}

    /**
     * Returns whether a tool with {@code name} survives {@code cfg}'s allow/deny rules.
     *
     * <p>This is useful when prompt construction depends on a tool being available after filtering.
     */
    public static boolean isAllowed(String name, ToolsConfig cfg) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (cfg == null) {
            return true;
        }
        List<String> deny = cfg.getDeny();
        if (deny != null && deny.contains(name)) {
            return false;
        }
        List<String> allow = cfg.getAllow();
        // MCP tools have their own per-server selection, independent of built-in defaults.
        if (!cfg.isStrictAllow()
                && cfg.getMcpServers() != null
                && cfg.getMcpServers().entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getValue().isPrefixToolNames()
                                                && name.startsWith(e.getKey() + "__"))) {
            return true;
        }
        return (!cfg.isStrictAllow() && HarnessPlatformTools.isPlatformTool(name))
                || (allow != null && allow.contains(name))
                || (cfg.isDefaultToolsEnabled() && (allow == null || allow.isEmpty()));
    }

    /**
     * Removes tools from {@code toolkit} that are excluded by {@code cfg}'s allow/deny lists. A
     * {@code null} {@code cfg} or one with no allow/deny entries is a no-op.
     */
    public static void apply(Toolkit toolkit, ToolsConfig cfg) {
        if (toolkit == null || cfg == null) {
            return;
        }
        List<String> allow = cfg.getAllow();
        List<String> deny = cfg.getDeny();
        boolean allowSet = allow != null && !allow.isEmpty();
        boolean denySet = deny != null && !deny.isEmpty();
        if (!allowSet && !denySet && cfg.isDefaultToolsEnabled()) {
            return;
        }

        Set<String> registered = new LinkedHashSet<>(toolkit.getToolNames());
        Set<String> allowSetView = allowSet ? new HashSet<>(allow) : null;
        Set<String> denySetView = denySet ? new HashSet<>(deny) : null;

        if (allowSetView != null) {
            warnUnknown(allowSetView, registered, "allow");
        }
        if (denySetView != null) {
            warnUnknown(denySetView, registered, "deny");
        }

        Set<String> toRemove = new LinkedHashSet<>();
        Set<String> protectedKept = new LinkedHashSet<>();
        for (String name : registered) {
            if (!isAllowed(name, cfg)) {
                toRemove.add(name);
            } else if (allowSetView != null
                    && !allowSetView.contains(name)
                    && HarnessPlatformTools.isPlatformTool(name)) {
                protectedKept.add(name);
            }
        }
        for (String name : toRemove) {
            toolkit.removeTool(name);
        }

        Set<String> remaining = new TreeSet<>(toolkit.getToolNames());
        if (protectedKept.isEmpty()) {
            log.info(
                    "tools.json filter applied: removed {} tool(s); {} tool(s) remain: {}",
                    toRemove.size(),
                    remaining.size(),
                    remaining);
        } else {
            log.info(
                    "tools.json filter applied: removed {} tool(s); kept {} platform tool(s)"
                            + " outside allow; {} tool(s) remain: {}",
                    toRemove.size(),
                    protectedKept.size(),
                    remaining.size(),
                    remaining);
        }
    }

    private static void warnUnknown(Set<String> declared, Set<String> registered, String which) {
        for (String name : declared) {
            if (!registered.contains(name)) {
                log.warn(
                        "tools.json '{}' references unknown tool '{}' (not currently registered).",
                        which,
                        name);
            }
        }
    }
}
