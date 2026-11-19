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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Acquire a session via {@link SandboxManager}</li>
 *   <li>Start the session (4-branch workspace init)</li>
 *   <li>Bind the live session on the per-call {@link RuntimeContext} for the
 *       {@link SandboxBackedFilesystem} proxy to resolve</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Release the session via {@link SandboxManager} (stop + optional shutdown)</li>
 *   <li>Persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Invalidate this call's session binding across {@link RuntimeContext} copies</li>
 * </ol>
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 *
 * <p>The sandbox is bound <em>per call</em> on the invocation's {@link RuntimeContext} rather than
 * on a shared agent-level slot: distinct {@code (userId, sessionId)} sessions run in parallel on
 * the same agent bean, so a shared slot would let concurrent calls corrupt each other's binding
 * (issue #2490).
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private volatile BeforeSandboxStart beforeStartCallback;
    private volatile boolean projectionAwareCallback;

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    /**
     * Session lifecycle is driven explicitly by HarnessAgent; participates at no point.
     * Subclasses overriding hooks must re-declare.
     */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.noneOf(ExtensionPoint.class);
    }

    /**
     * Before-start callback that can also narrow the projected {@code .skills-cache} subtree.
     * Runs after the sandbox session is acquired but before {@link
     * io.agentscope.harness.agent.sandbox.Sandbox#start()} applies workspace projection.
     */
    @FunctionalInterface
    public interface BeforeSandboxStart {

        /**
         * Materialises pre-start host resources (e.g. stages {@code .skills-cache/}).
         *
         * @param ctx the per-call {@link RuntimeContext}
         * @return the {@code .skills-cache} projection roots this call is granted — the
         *     configured whole-tree {@code .skills-cache} include-root is replaced by these
         *     explicit per-skill subtrees; {@code null} keeps the configured projection
         */
        List<String> beforeStart(RuntimeContext ctx);
    }

    /**
     * Registers a callback that runs after the sandbox session is acquired but before
     * {@link io.agentscope.harness.agent.sandbox.Sandbox#start()} applies workspace projection.
     * This allows callers to materialise resources on the host workspace (e.g.
     * {@code .skills-cache/}) so that projection picks them up in the same call. The callback
     * cannot narrow the projection; see {@link #setProjectionAwareBeforeStart(BeforeSandboxStart)}.
     *
     * @param callback receives the per-call {@link RuntimeContext}; may be {@code null} to clear
     */
    public void setBeforeStartCallback(Consumer<RuntimeContext> callback) {
        this.projectionAwareCallback = false;
        this.beforeStartCallback =
                callback != null
                        ? ctx -> {
                            callback.accept(ctx);
                            return null;
                        }
                        : null;
    }

    /**
     * Registers a projection-aware before-start callback: besides materialising pre-start host
     * resources, its return value narrows the {@code .skills-cache} workspace projection to the
     * explicit per-skill cache roots granted for this call (grant-layer guarantee: cache
     * directories staged by earlier calls under wider grants stay on disk but no longer project
     * into this call's sandbox). The shared configured {@link SandboxContext} is never mutated —
     * narrowing derives a per-call copy, so concurrent calls keep independent views.
     *
     * @param callback the before-start hook; may be {@code null} to clear
     */
    public void setProjectionAwareBeforeStart(BeforeSandboxStart callback) {
        this.projectionAwareCallback = callback != null;
        this.beforeStartCallback = callback;
    }

    /**
     * Acquires the sandbox for the current call. Runs on the harness call path (before
     * the agent's reasoning begins) so the sandbox is available for both the
     * {@code call()} and {@code streamEvents()} flows.
     *
     * @param ctx the per-call RuntimeContext (must not be null)
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        try {
            BeforeSandboxStart cb = beforeStartCallback;
            // A projection-aware callback carries the authorization decision for the cache
            // subtree: when it fails, nothing of the cache may project (fail-closed). The
            // legacy Consumer variant carries no grant decision — its failure keeps the
            // configured projection (historical behaviour).
            boolean projectionAware = cb != null && projectionAwareCallback;
            List<String> cacheRoots = null;
            if (cb != null) {
                try {
                    cacheRoots = cb.beforeStart(ctx);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-mw] beforeStartCallback failed; {}: {}",
                            projectionAware
                                    ? "failing closed — no .skills-cache projects for this call"
                                    : "proceeding with sandbox start",
                            e.getMessage(),
                            e);
                    if (projectionAware) {
                        cacheRoots = List.of();
                    }
                }
            }
            if (cacheRoots != null) {
                boolean authorizationEngaged = isAuthorizationEngaged(ctx);
                SandboxContext narrowed =
                        narrowSkillsCacheProjection(
                                sandboxContext, cacheRoots, authorizationEngaged);
                if (narrowed == sandboxContext) {
                    if (cacheRoots.isEmpty()) {
                        // Nothing granted, nothing narrowable — expected for cache-less specs.
                        log.debug(
                                "[sandbox-mw] no cache projection entry to narrow for this"
                                        + " sandbox");
                    } else {
                        log.warn(
                                "[sandbox-mw] projection-aware callback produced cache roots"
                                        + " but the workspace spec has no .skills-cache"
                                        + " projection entry; grant narrowing is NOT applied"
                                        + " to this sandbox (external sandbox or misconfigured"
                                        + " spec?)");
                    }
                } else if (authorizationEngaged
                        && (sandboxContext.getExternalSandbox() != null
                                || sandboxContext.getExternalSandboxState() != null)) {
                    // The narrowed spec is silently discarded by the external-sandbox
                    // acquire priorities — surface it instead of a silent unmanaged cache.
                    log.warn(
                            "[sandbox-mw] grant narrowing derived for an external sandbox"
                                    + " context; external sandboxes project by their own spec"
                                    + " and ignore the narrowed workspaceSpec (no revocation"
                                    + " applies)");
                }
                sandboxContext = narrowed;
            }
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            PinnedSandboxFilesystem.markSandboxAcquired(sandbox);
            try {
                sandbox.start();
                // Bind the acquired sandbox per-call on this invocation's RuntimeContext rather
                // than only on a shared agent-level slot. Distinct (userId, sessionId) sessions
                // run in parallel on the same agent bean, so a shared slot lets concurrent calls
                // corrupt each other's binding (issue #2490). The filesystem proxy resolves the
                // sandbox from this context first; the field below is a best-effort fallback for
                // context-free callers (e.g. WorkspaceMessageBus) that do not thread a per-call.
                ctx.put(SandboxAcquireResult.class, result);
                filesystemProxy.setSandbox(sandbox);
                log.debug(
                        "[sandbox-mw] Acquired sandbox {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
            } catch (Exception e) {
                ctx.put(SandboxAcquireResult.class, null);
                filesystemProxy.clearSandboxIfCurrent(sandbox);
                try {
                    markMirrorSandboxReleased(result);
                    sandboxManager.release(result);
                } catch (Exception releaseErr) {
                    log.warn(
                            "[sandbox-mw] Failed to release session after pre-call failure: {}",
                            releaseErr.getMessage(),
                            releaseErr);
                }
                result.getLease().close();
                throw e;
            }
        } catch (Exception e) {
            log.error("[sandbox-mw] Failed to acquire/start sandbox", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Releases the sandbox after the current call. Called from
     * {@code ReActAgent.afterAgentExecution()} to ensure cleanup for both paths.
     *
     * @param ctx the per-call RuntimeContext (captured at acquire time)
     */
    public void releaseForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        // Read back the binding this same call established in acquireForCall, so a call only ever
        // tears down its own sandbox — never a concurrent session's (issue #2490).
        SandboxAcquireResult result = ctx.get(SandboxAcquireResult.class);
        if (result == null) {
            return;
        }
        // Retain an invalidated binding so this context cannot fall back to a sibling call.
        if (!result.beginRelease()) {
            return;
        }
        // Compare-and-clear the fallback field so a releasing call never nulls a concurrent
        // sibling's binding (issue #2490); it only clears the field when it still points here.
        filesystemProxy.clearSandboxIfCurrent(result.getSandbox());
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        // Release (stop/persist workspace) first so state mutations made during stop — e.g.
        // workspaceRootReady or per-session snapshot records — are captured by the persist below.
        try {
            markMirrorSandboxReleased(result);
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        try {
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        result.getLease().close();
    }

    private static void markMirrorSandboxReleased(SandboxAcquireResult result) {
        if (result.isSelfManaged()) {
            PinnedSandboxFilesystem.markSandboxReleased(result.getSandbox());
        }
    }

    /**
     * Replaces the configured whole-tree {@code .skills-cache} include-root with the explicit
     * per-skill cache subtrees of this call, on a derived {@link SandboxContext}/{@link
     * WorkspaceSpec} copy. The shared configured context is never mutated, so concurrent calls
     * against the same agent bean keep independent projection views (issue #2490's per-call
     * binding, applied to the spec). Contexts whose projection does not cover {@code
     * .skills-cache} are returned unchanged.
     *
     * @param original the per-call sandbox context from the {@link RuntimeContext}
     * @param cacheRoots the granted {@code .skills-cache} subtrees (workspace-relative,
     *     forward slashes); an empty list removes the cache from projection entirely
     * @param authorizationEngaged whether this call runs under a restricted capability
     *     surface: managed (revoking) semantics are gated on authorization being engaged,
     *     so deployments that never enable the grant layer keep the generic overlay
     *     projection behaviour regardless of stager presence
     * @return a derived context with the narrowed projection, or {@code original} when nothing
     *     can (or needs to) be narrowed
     */
    private static SandboxContext narrowSkillsCacheProjection(
            SandboxContext original, List<String> cacheRoots, boolean authorizationEngaged) {
        WorkspaceSpec spec = original.getWorkspaceSpec();
        if (spec == null) {
            return original;
        }
        WorkspaceEntry entry =
                spec.getEntries().get(SandboxFilesystemSpec.WORKSPACE_PROJECTION_ENTRY_KEY);
        if (!(entry instanceof WorkspaceProjectionEntry projection) || !hasCacheRoot(projection)) {
            return original;
        }
        WorkspaceSpec narrowedSpec = spec.copy();
        WorkspaceProjectionEntry narrowed = new WorkspaceProjectionEntry();
        narrowed.setSourceRoot(projection.getSourceRoot());
        narrowed.setEphemeral(projection.isEphemeral());
        // The narrowing itself (per-skill cache subtrees of this call's enabled set) is
        // the maintainer-requested per-call projection constraint ("需要约束当前调用
        // 可访问/投影的文件集合") and applies whenever a stager is wired. The managed
        // marker — authoritative revocation and rebuild — is additionally gated on the
        // call running under a restricted capability surface: deployments that never
        // enable the grant layer keep pure overlay semantics. The revoke roots name the
        // whole cache namespace so a managed pass that cannot trust its bookkeeping can
        // rebuild it.
        narrowed.setAuthorizationManaged(authorizationEngaged);
        if (authorizationEngaged) {
            narrowed.setRevokeRoots(List.of(MarketplaceStager.CACHE_DIR));
        }
        List<String> roots =
                new ArrayList<>(projection.getIncludeRoots().size() + cacheRoots.size());
        for (String root : projection.getIncludeRoots()) {
            // Variant spellings of the cache root (leading ./, trailing /) project the
            // same tree; normalize the match so narrowing is never silently disabled.
            if (!isCacheRoot(root)) {
                roots.add(root);
            }
        }
        roots.addAll(cacheRoots);
        narrowed.setIncludeRoots(roots);
        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>(narrowedSpec.getEntries());
        entries.put(SandboxFilesystemSpec.WORKSPACE_PROJECTION_ENTRY_KEY, narrowed);
        narrowedSpec.setEntries(entries);
        return SandboxContext.builder()
                .client(original.getClient())
                .clientOptions(original.getClientOptions())
                .workspaceSpec(narrowedSpec)
                .snapshotSpec(original.getSnapshotSpec())
                .externalSandbox(original.getExternalSandbox())
                .externalSandboxState(original.getExternalSandboxState())
                .isolationScope(original.getIsolationScope())
                .build();
    }

    private static boolean hasCacheRoot(WorkspaceProjectionEntry projection) {
        return projection.getIncludeRoots().stream()
                .anyMatch(SandboxLifecycleMiddleware::isCacheRoot);
    }

    /**
     * Variant-tolerant cache-root match: any spelling that resolves to the same tree as
     * the canonical {@code .skills-cache} (leading {@code ./}, trailing {@code /},
     * interior {@code //} or {@code .} segments) counts, mirroring how
     * {@code WorkspaceProjectionApplier} normalizes roots — a variant spelling must not
     * silently disable narrowing.
     */
    private static boolean isCacheRoot(String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        try {
            return java.nio.file.Path.of(root)
                    .normalize()
                    .equals(java.nio.file.Path.of(MarketplaceStager.CACHE_DIR));
        } catch (java.nio.file.InvalidPathException e) {
            // A merely-configured root with platform-illegal characters is a non-match,
            // not a call failure (it fails later in staging if it is ever used at all).
            return false;
        }
    }

    /**
     * Whether this call runs under an engaged authorization: a restricted capability
     * surface on the context. Unrestricted (or absent) surfaces mean the grant layer is
     * not in play and projections keep the generic overlay semantics.
     */
    private static boolean isAuthorizationEngaged(RuntimeContext ctx) {
        CapabilitySurface surface = ctx != null ? ctx.get(CapabilitySurface.class) : null;
        return surface != null && !surface.isUnrestricted();
    }
}
