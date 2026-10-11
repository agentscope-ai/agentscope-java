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
package io.agentscope.core.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Manages MCP (Model Context Protocol) client registration and lifecycle.
 * Handles MCP client initialization, tool registration, and cleanup.
 */
class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    /**
     * Tracking entry per MCP client name: the registered wrapper, a completed flag set by the
     * first successful attempt bound to it, the count of in-flight attempts, and the tools any
     * bound attempt managed to register. The LAST attempt to leave an uncompleted claim
     * releases it — rolling back the recorded tools and closing the wrapper; a completed claim
     * is never released by late-finishing failures, and a removed claim is never resurrected by
     * a late success (which then rolls back its own tools instead).
     */
    private record ClientClaim(
            McpClientWrapper wrapper,
            AtomicBoolean completed,
            AtomicInteger inFlight,
            List<AgentTool> registeredTools) {}

    private final Map<String, ClientClaim> mcpClients = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolRegistrationCallback registrationCallback;

    /**
     * Callback interface for tool registration.
     */
    @FunctionalInterface
    interface ToolRegistrationCallback {
        void registerAgentToolWithMcpClient(
                AgentTool tool,
                String groupName,
                String mcpClientName,
                Map<String, Object> presetParameters);
    }

    McpClientManager(
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolRegistrationCallback registrationCallback) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.registrationCallback = registrationCallback;
    }

    /**
     * Registers an MCP client and all its tools.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return registerMcpClient(mcpClientWrapper, null, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper, List<String> enableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, null, null);
    }

    /**
     * Registers an MCP client with tool filtering.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, null);
    }

    /**
     * Registers an MCP client with tool filtering and group assignment.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, groupName, null);
    }

    /**
     * Registers an MCP client with tool filtering, group assignment, and preset parameters.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                "");
    }

    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                null);
    }

    /**
     * Registers an MCP client with full control over tool filtering, grouping, preset parameters,
     * naming and metadata propagation.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @param toolNamePrefix optional namespace prefix for model-facing tool names
     * @param propagateMetaOverride registration-level default for metadata propagation on the
     *     registered tools; {@code null} means {@code true} (the connection-level switch on the
     *     wrapper is still applied live at call time)
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                propagateMetaOverride,
                null);
    }

    /**
     * Registers an MCP client with full control over tool filtering, grouping, preset parameters,
     * naming and per-tool metadata propagation.
     *
     * @param mcpClientWrapper the MCP client wrapper
     * @param enableTools list of tool names to enable (null means enable all)
     * @param disableTools list of tool names to disable (null means disable none)
     * @param groupName the group name to assign MCP tools to
     * @param presetParametersMapping map from tool name to preset parameters for that tool
     * @param toolNamePrefix optional namespace prefix for model-facing tool names
     * @param propagateMetaOverride registration-level default for metadata propagation on the
     *     registered tools; {@code null} means {@code true}
     * @param toolPropagateMetaOverrides per-tool metadata propagation overrides keyed by the
     *     remote MCP tool name (before any {@code toolNamePrefix}); an entry wins over
     *     {@code propagateMetaOverride}. Keys that do not match a tool that is actually
     *     registered from this client (unknown name, remote rename, or filtered out by
     *     {@code enableTools}/{@code disableTools}) fail the registration with an
     *     {@link IllegalArgumentException} so a silencing override is never lost silently
     *
     * <p>A wrapper whose configured name is already tracked by a <em>different</em> wrapper is
     * rejected with an {@link IllegalStateException} error signal before the connection is
     * opened: two live clients under one name would take over each other's tool registrations
     * and leave the first wrapper unreachable for {@link #removeMcpClient(String)}. Call
     * {@code removeMcpClient} first, or register the new client under a distinct name.
     * Re-registering the <em>same</em> wrapper instance stays an idempotent refresh (reconnect /
     * tool-list refresh).
     *
     * <p>Attempts on one client name share a claim that counts them in. A failure (or cancel)
     * of an attempt leaves the claim alone while another attempt bound to it is still running,
     * and forever after any attempt on that claim has completed. Only the LAST attempt out of
     * a claim that never completed releases it: the tracking entry is removed, the tools that
     * client managed to register are rolled back, and the wrapper is closed best-effort (for
     * stdio transports it owns a spawned subprocess). Such a wrapper must not be reused.
     *
     * <p>A late success whose claim was already removed (e.g. by
     * {@link #removeMcpClient(String)} during flight) neither resurrects the tracking entry
     * nor marks a re-created claim under the same name; its tool writes are rolled back like a
     * failure's. Each tool write also re-checks that the attempt's claim is still the tracked
     * one, so an attempt continues to fail loud the moment its client is removed mid-flight
     * instead of orphaning further tools on the closing connection.
     *
     * @return Mono that completes when registration is finished
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride,
            Map<String, Boolean> toolPropagateMetaOverrides) {
        if (mcpClientWrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
        }

        // Validate group exists if specified
        if (groupName != null) {
            try {
                groupManager.validateGroupExists(groupName);
            } catch (IllegalArgumentException e) {
                return Mono.error(e);
            }
        }

        // Admission and its cleanup run at SUBSCRIPTION time (Mono.defer): merely building the
        // Mono must never reserve the client name, so an unsubscribed attempt cannot strand it.
        // Attempts on one name share a claim that counts them in; the LAST attempt out of a
        // claim that never completed releases it — so concurrent same-wrapper attempts can
        // neither close a connection another attempt is still using, nor strand a wrapper no
        // live registration owns.
        return Mono.defer(
                () -> {
                    final ClientClaim myClaim = admitClientName(mcpClientWrapper);
                    // Runs once per attempt on ANY terminal signal. Under the per-name lock:
                    // decrement the in-flight count; if this was the last attempt out of the
                    // still-current, still-uncompleted claim, release it; if the attempt
                    // COMPLETED while its claim had already been removed or replaced (stale),
                    // it owns orphaned tool writes that must go back. Either way the claim's
                    // recorded tools are rolled back instance-by-instance, so a binding a later
                    // successful registration superseded is left untouched, and group
                    // membership never keeps a ghost entry.
                    final Consumer<SignalType> attemptFinished =
                            signal -> {
                                final AtomicBoolean released = new AtomicBoolean(false);
                                final AtomicBoolean stale = new AtomicBoolean(false);
                                mcpClients.compute(
                                        mcpClientWrapper.getName(),
                                        (claimName, current) -> {
                                            int remaining = myClaim.inFlight().decrementAndGet();
                                            if (current == myClaim) {
                                                if (signal != SignalType.ON_COMPLETE
                                                        && !myClaim.completed().get()
                                                        && remaining == 0) {
                                                    released.set(true);
                                                    return null;
                                                }
                                            } else {
                                                // The claim is gone or was replaced: whatever
                                                // this attempt wrote (successful completion or
                                                // mid-stream failure after the removal) would
                                                // orphan onto the untracked connection — roll it
                                                // back. Instance-guarded below, so bindings a
                                                // newer registration owns stay untouched.
                                                stale.set(true);
                                            }
                                            return current;
                                        });
                                if (released.get() || stale.get()) {
                                    // Snapshot + clear under the list's lock: the claim is
                                    // shared across same-wrapper attempts, and synchronizedList
                                    // iteration is only thread-safe under an explicit lock.
                                    final List<AgentTool> rollback = new ArrayList<>();
                                    synchronized (myClaim.registeredTools()) {
                                        rollback.addAll(myClaim.registeredTools());
                                        myClaim.registeredTools().clear();
                                    }
                                    for (AgentTool tool : rollback) {
                                        if (toolRegistry.removeToolIfSame(tool.getName(), tool)) {
                                            groupManager.removeToolFromAllGroups(tool.getName());
                                            logger.debug(
                                                    "Rolled back MCP tool: {}", tool.getName());
                                        }
                                    }
                                }
                                if (released.get()) {
                                    // The client may have been initialized already; now that it
                                    // is also untracked, nobody else can reach or close it (for
                                    // stdio transports it owns a spawned subprocess).
                                    // Best-effort cleanup must not mask the original error.
                                    try {
                                        mcpClientWrapper.close();
                                    } catch (RuntimeException cleanupEx) {
                                        logger.debug(
                                                "Failed to close MCP client '{}' after a failed"
                                                        + " registration",
                                                mcpClientWrapper.getName(),
                                                cleanupEx);
                                    }
                                }
                            };

                    logger.info("Registering MCP client: {}", mcpClientWrapper.getName());

                    // initialize() is deferred as well: a wrapper that throws SYNCHRONOUSLY
                    // from it (external subclasses may) must surface as an onError signal so
                    // the cleanup handler below runs, instead of escaping this supplier after
                    // the claim was bound — which would strand the name and leak the
                    // connection.
                    return Mono.defer(mcpClientWrapper::initialize)
                            .then(Mono.defer(mcpClientWrapper::listTools))
                            .flatMapMany(
                                    tools -> {
                                        // Fail loud when a per-tool propagateMeta override does not
                                        // match
                                        // any tool that is actually registered from this client
                                        // (unknown
                                        // name, remote rename, or filtered out by enable/disable
                                        // lists).
                                        // This feature exists to keep metadata off the wire for
                                        // untrusted
                                        // servers, so a silencing override must never be lost
                                        // silently.
                                        if (toolPropagateMetaOverrides != null
                                                && !toolPropagateMetaOverrides.isEmpty()) {
                                            Set<String> registeredNames =
                                                    tools.stream()
                                                            .map(tool -> tool.name())
                                                            .filter(
                                                                    toolName ->
                                                                            shouldRegisterTool(
                                                                                    toolName,
                                                                                    enableTools,
                                                                                    disableTools))
                                                            .collect(Collectors.toSet());
                                            // TreeSet so the rejected names in the user-facing
                                            // error
                                            // message are deterministically ordered and pasteable.
                                            Set<String> unknown =
                                                    new TreeSet<>(
                                                            toolPropagateMetaOverrides.keySet());
                                            unknown.removeAll(registeredNames);
                                            if (!unknown.isEmpty()) {
                                                return Flux.error(
                                                        new IllegalArgumentException(
                                                                "Unknown MCP tool(s) in"
                                                                    + " propagateMeta override for"
                                                                    + " client '"
                                                                        + mcpClientWrapper.getName()
                                                                        + "': "
                                                                        + unknown));
                                            }
                                        }
                                        return Flux.fromIterable(tools);
                                    })
                            .filter(
                                    tool ->
                                            shouldRegisterTool(
                                                    tool.name(), enableTools, disableTools))
                            .doOnNext(
                                    mcpTool -> {
                                        // Fail loud if this attempt's claim is no longer the
                                        // tracked one: the client was removed (or re-created)
                                        // mid-flight, so anything written from here on would
                                        // orphan onto an untracked, already-closed connection
                                        // and could even block the fresh registration under the
                                        // same name with stale tool bindings.
                                        if (mcpClients.get(mcpClientWrapper.getName()) != myClaim) {
                                            throw new IllegalStateException(
                                                    "MCP client '"
                                                            + mcpClientWrapper.getName()
                                                            + "' was removed or replaced while"
                                                            + " its registration was in flight");
                                        }

                                        logger.debug(
                                                "Registering MCP tool: {} from client {} into group"
                                                        + " {}",
                                                mcpTool.name(),
                                                mcpClientWrapper.getName(),
                                                groupName);

                                        // Get preset parameters for this specific tool
                                        Map<String, Object> toolPresetParams =
                                                presetParametersMapping != null
                                                        ? presetParametersMapping.get(
                                                                mcpTool.name())
                                                        : null;

                                        boolean readOnly =
                                                mcpTool.annotations() != null
                                                        && Boolean.TRUE.equals(
                                                                mcpTool.annotations()
                                                                        .readOnlyHint());

                                        McpTool agentTool =
                                                new McpTool(
                                                        toolNamePrefix + mcpTool.name(),
                                                        mcpTool.name(),
                                                        mcpTool.description() != null
                                                                ? mcpTool.description()
                                                                : "",
                                                        McpTool.convertMcpSchemaToParameters(
                                                                mcpTool.inputSchema(),
                                                                toolPresetParams != null
                                                                        ? toolPresetParams.keySet()
                                                                        : Collections.emptySet()),
                                                        mcpTool.outputSchema() != null
                                                                ? new ConcurrentHashMap<>(
                                                                        mcpTool.outputSchema())
                                                                : null,
                                                        mcpClientWrapper,
                                                        /* presetArguments handled upstream by
                                                         * RegisteredToolFunction */ null,
                                                        mcpClientWrapper.getName(),
                                                        readOnly);

                                        // Per-tool metadata propagation restriction, resolved at
                                        // registration time: per-tool override > registration
                                        // default >
                                        // true. The connection-level wrapper switch is NOT captured
                                        // here;
                                        // McpTool reads it live on every call (logical AND), so
                                        // disabling a
                                        // connection later still stops metadata immediately.
                                        Boolean perToolOverride =
                                                toolPropagateMetaOverrides != null
                                                        ? toolPropagateMetaOverrides.get(
                                                                mcpTool.name())
                                                        : null;
                                        boolean propagateMeta =
                                                perToolOverride != null
                                                        ? perToolOverride
                                                        : propagateMetaOverride != null
                                                                ? propagateMetaOverride
                                                                : true;
                                        agentTool.setPropagateMeta(propagateMeta);

                                        // Register with group, MCP client name, and preset
                                        // parameters via
                                        // callback
                                        registrationCallback.registerAgentToolWithMcpClient(
                                                agentTool,
                                                groupName,
                                                mcpClientWrapper.getName(),
                                                toolPresetParams);

                                        // Record on the shared claim so that whichever attempt
                                        // eventually releases it rolls exactly these bindings
                                        // back (instance-guarded), never a later registration's.
                                        myClaim.registeredTools().add(agentTool);
                                    })
                            .then()
                            .doOnSuccess(
                                    v -> {
                                        // Mark MY claim completed under the per-name lock, so
                                        // no later attempt bound to it may release-and-close
                                        // the live registration. Reference equality: a stale
                                        // attempt whose claim was already removed — possibly
                                        // re-created under the same name by the same wrapper
                                        // instance — must not mark the foreign claim. If the
                                        // entry is gone, computeIfPresent is a no-op and a
                                        // removed client is never resurrected.
                                        ClientClaim tracked =
                                                mcpClients.computeIfPresent(
                                                        mcpClientWrapper.getName(),
                                                        (name, current) -> {
                                                            if (current == myClaim) {
                                                                myClaim.completed().set(true);
                                                            }
                                                            return current;
                                                        });
                                        // Only log success when this attempt's claim is the
                                        // tracked one; a stale completion gets rolled back in
                                        // doFinally, and a "successfully registered" line for it
                                        // would contradict that cleanup during troubleshooting.
                                        if (tracked == myClaim) {
                                            logger.info(
                                                    "MCP client '{}' registered successfully",
                                                    mcpClientWrapper.getName());
                                        }
                                    })
                            .doOnError(
                                    e ->
                                            logger.error(
                                                    "Failed to register MCP client: {}",
                                                    mcpClientWrapper.getName(),
                                                    e))
                            // One cleanup hook for every terminal signal: completion runs it
                            // after the claim was marked completed (no-op unless the claim was
                            // meanwhile removed, which makes the completion stale), errors and
                            // cancels run the last-one-out release+rollback+close.
                            .doFinally(attemptFinished);
                });
    }

    /**
     * Binds this registration attempt to the claim tracked under its client name.
     *
     * <p>The attempt that finds the name untracked CREATES the claim; a same-wrapper attempt
     * joins the existing claim and bumps its in-flight count WITHOUT re-pointing it — stealing
     * the claim would strand the entry when both attempts fail, each considering the other
     * responsible for release. A distinct wrapper claiming a tracked name is rejected
     * fail-loud.
     *
     * @param wrapper the wrapper being registered
     * @return the claim this attempt is bound to (created or joined)
     * @throws IllegalStateException when a different wrapper already tracks this name; raised
     *     inside {@code ConcurrentHashMap#compute} — the JDK contract propagates it to the
     *     caller and leaves the mapping untouched — and surfaces to subscribers as the returned
     *     Mono's error signal ({@code Mono.defer} converts supplier throws)
     */
    private ClientClaim admitClientName(McpClientWrapper wrapper) {
        AtomicReference<ClientClaim> bound = new AtomicReference<>();
        mcpClients.compute(
                wrapper.getName(),
                (name, existing) -> {
                    if (existing == null) {
                        ClientClaim created =
                                new ClientClaim(
                                        wrapper,
                                        new AtomicBoolean(false),
                                        new AtomicInteger(1),
                                        Collections.synchronizedList(new ArrayList<>()));
                        bound.set(created);
                        return created;
                    }
                    if (existing.wrapper() != wrapper) {
                        throw new IllegalStateException(
                                "MCP client name '"
                                        + name
                                        + "' is already registered by a different MCP client"
                                        + " wrapper. Call removeMcpClient(\""
                                        + name
                                        + "\") first, or register the new client under a distinct"
                                        + " name.");
                    }
                    existing.inFlight().incrementAndGet();
                    bound.set(existing);
                    return existing;
                });
        return bound.get();
    }

    /**
     * Removes an MCP client and all its tools.
     *
     * <p>The tools are swept by client name after the tracking claim is removed. A registration
     * of the same name racing <em>inside</em> that small window (claim already gone, sweep not
     * yet run) can have its fresh tools swept with the old ones — the would-be remover cannot
     * distinguish them by name alone. Concurrent remove-plus-re-register under one name is a
     * caller-sequencing responsibility (await the removal before re-adding); removing a client
     * whose registration is merely in flight is fully supported and cleans up after it.
     *
     * @param mcpClientName the name of the MCP client to remove
     * @return Mono that completes when removal is finished
     */
    Mono<Void> removeMcpClient(String mcpClientName) {
        ClientClaim claim = mcpClients.remove(mcpClientName);
        McpClientWrapper wrapper = claim == null ? null : claim.wrapper();
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        removeToolsOfClient(mcpClientName);

        return Mono.fromRunnable(wrapper::close)
                .then()
                .doOnSuccess(
                        v -> logger.info("MCP client '{}' removed successfully", mcpClientName));
    }

    /**
     * Removes every tool currently registered for the given MCP client name, including their
     * group memberships. Used by {@link #removeMcpClient(String)}: leaving a ghost entry in the
     * group index would make {@code isGroupedTool} report the name grouped forever, silently
     * filtering a future registration of the same name as grouped-but-inactive. Rolled-back
     * failed registrations use the claim's own recorded tools instead (instance-guarded), so
     * this name-based sweep never races with them.
     */
    private void removeToolsOfClient(String mcpClientName) {
        List<String> toolsToRemove =
                toolRegistry.getAllRegisteredTools().values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(reg -> reg.getTool().getName())
                        .collect(Collectors.toList());
        toolsToRemove.forEach(
                toolName -> {
                    toolRegistry.removeTool(toolName);
                    groupManager.removeToolFromAllGroups(toolName);
                    logger.debug("Removed MCP tool: {}", toolName);
                });
    }

    /**
     * Gets all registered MCP client names.
     *
     * <p><b>In-flight entries.</b> A name is claimed when {@code registerMcpClient} is
     * SUBSCRIBED, so this view can contain clients whose registration is still running (and may
     * still fail or be rolled back). Treat membership as "a registration was started for this
     * name", not "this client is ready"; only {@code registerMcpClient(...)} completion and
     * {@link #removeMcpClient(String)} change it atomically with the tools. Removing an
     * in-flight entry is supported: the running attempt fails loud on its next tool write, or
     * rolls its own tools back if it completes stale.
     *
     * @return set of MCP client names, never null but may be empty
     */
    Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * Gets an MCP client wrapper by name.
     *
     * <p>The returned wrapper may belong to an in-flight registration (see
     * {@link #getMcpClientNames()}) — it is not guaranteed initialized or fully registered.
     *
     * @param name the MCP client name
     * @return the MCP client wrapper, or null if not found
     */
    McpClientWrapper getMcpClient(String name) {
        ClientClaim claim = mcpClients.get(name);
        return claim == null ? null : claim.wrapper();
    }

    /**
     * Determines if a tool should be registered based on enable/disable lists.
     *
     * @param toolName the tool name
     * @param enableTools list of tools to enable (null means all), takes precedence over disableTools
     * @param disableTools list of tools to disable (null means none)
     * @return true if the tool should be registered
     */
    private boolean shouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) {
        // Default: register all tools
        boolean result = true;

        if (disableTools != null && !disableTools.isEmpty()) {
            result = !disableTools.contains(toolName);
        }

        if (enableTools != null && !enableTools.isEmpty()) {
            result = enableTools.contains(toolName);
        }

        return result;
    }
}
