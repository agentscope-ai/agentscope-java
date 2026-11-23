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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a projection-aware before-start callback narrows the {@code .skills-cache}
 * workspace projection to the explicit per-skill cache roots of the current call — on a
 * derived {@link SandboxContext}, never by mutating the shared configured one (concurrent
 * calls keep independent views).
 */
class SandboxLifecycleMiddlewareProjectionNarrowingTest {

    /** Minimal no-op sandbox: {@code acquireForCall} only needs {@code start()}/{@code getState()}. */
    private static final class NoopSandbox implements Sandbox {

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    private static final class NoopLease implements SandboxLease {
        @Override
        public void close() {}
    }

    /** Mirrors the entry {@code SandboxFilesystemSpec} registers for workspace projection. */
    private static SandboxContext contextWithProjection(List<String> includeRoots) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot("C:/ws");
        projection.setIncludeRoots(includeRoots);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("__workspace_projection__", projection);
        return SandboxContext.builder().workspaceSpec(spec).build();
    }

    /** Manager that records the context it was handed and returns a self-managed no-op sandbox. */
    private static SandboxManager capturingManager(AtomicReference<SandboxContext> captured) {
        return new SandboxManager(
                mock(SandboxClient.class), mock(SessionSandboxStateStore.class), "narrow-agent") {
            @Override
            public SandboxAcquireResult acquire(
                    SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                captured.set(sandboxContext);
                return SandboxAcquireResult.selfManaged(new NoopSandbox(), new NoopLease());
            }
        };
    }

    private static List<String> projectionRoots(SandboxContext ctx) {
        WorkspaceEntry entry = ctx.getWorkspaceSpec().getEntries().get("__workspace_projection__");
        return ((WorkspaceProjectionEntry) entry).getIncludeRoots();
    }

    @Test
    void narrowedRootsReplaceTheWholeCacheRootWithoutMutatingTheSharedContext() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original =
                contextWithProjection(List.of("AGENTS.md", "skills", ".skills-cache"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setProjectionAwareBeforeStart(rc -> List.of(".skills-cache/u1/market/alpha"));
        mw.acquireForCall(ctx);

        assertEquals(
                List.of("AGENTS.md", "skills", ".skills-cache/u1/market/alpha"),
                projectionRoots(captured.get()),
                "the whole-tree .skills-cache root must be replaced by the granted subtrees");
        assertEquals(
                List.of("AGENTS.md", "skills", ".skills-cache"),
                projectionRoots(original),
                "the shared configured context must never be mutated");
    }

    @Test
    void emptyRootsRemoveTheCacheFromProjectionEntirely() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original =
                contextWithProjection(List.of("AGENTS.md", "skills", ".skills-cache"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setProjectionAwareBeforeStart(rc -> List.of());
        mw.acquireForCall(ctx);

        assertEquals(
                List.of("AGENTS.md", "skills"),
                projectionRoots(captured.get()),
                "an empty grant list must project nothing of the cache");
    }

    @Test
    void managedMarkerIsGatedOnAuthorizationBeingEngaged() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original =
                contextWithProjection(List.of("AGENTS.md", "skills", ".skills-cache"));

        // No capability surface on the context (authorization never engaged): narrowing
        // still applies (historic stager behaviour), but the projection stays UNMANAGED —
        // no revocation, no rebuild: the generic semantics for non-grant deployments.
        RuntimeContext plainCtx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();
        mw.setProjectionAwareBeforeStart(rc -> List.of(".skills-cache/u1/market/alpha"));
        mw.acquireForCall(plainCtx);
        WorkspaceProjectionEntry unmanaged =
                (WorkspaceProjectionEntry)
                        captured.get()
                                .getWorkspaceSpec()
                                .getEntries()
                                .get("__workspace_projection__");
        assertFalse(unmanaged.isAuthorizationManaged(), "no surface engaged: unmanaged");
        assertTrue(unmanaged.getRevokeRoots().isEmpty(), "no revoke roots without engagement");

        // A restricted surface on the context: the same narrowing becomes managed.
        RuntimeContext grantedCtx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .put(
                                CapabilitySurface.class,
                                new CapabilitySurface(Set.of(), Set.of(), Set.of()))
                        .build();
        mw.acquireForCall(grantedCtx);
        WorkspaceProjectionEntry managed =
                (WorkspaceProjectionEntry)
                        captured.get()
                                .getWorkspaceSpec()
                                .getEntries()
                                .get("__workspace_projection__");
        assertTrue(managed.isAuthorizationManaged(), "restricted surface: managed");
        assertEquals(
                List.of(".skills-cache"),
                managed.getRevokeRoots(),
                "the whole cache namespace is the rebuild scope");
    }

    @Test
    void projectionAwareCallbackFailureFailsClosedToNoCache() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original =
                contextWithProjection(List.of("AGENTS.md", "skills", ".skills-cache"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setProjectionAwareBeforeStart(
                rc -> {
                    throw new IllegalStateException("simulated staging failure");
                });
        mw.acquireForCall(ctx);

        assertEquals(
                List.of("AGENTS.md", "skills"),
                projectionRoots(captured.get()),
                "a projection-aware callback carries the authorization decision for the cache:"
                        + " its failure must project nothing of .skills-cache (fail-closed)");
        assertEquals(
                List.of("AGENTS.md", "skills", ".skills-cache"),
                projectionRoots(original),
                "the shared configured context must never be mutated");
    }

    @Test
    void explicitNullReturnKeepsTheConfiguredProjection() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original = contextWithProjection(List.of("AGENTS.md", ".skills-cache"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setProjectionAwareBeforeStart(rc -> null);
        mw.acquireForCall(ctx);

        assertSame(
                original,
                captured.get(),
                "an explicit null return means 'no opinion': keep the configured projection");
    }

    @Test
    void legacyConsumerFailureKeepsTheConfiguredProjection() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original = contextWithProjection(List.of("AGENTS.md", ".skills-cache"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setBeforeStartCallback(
                rc -> {
                    throw new IllegalStateException("simulated legacy failure");
                });
        mw.acquireForCall(ctx);

        assertSame(
                original,
                captured.get(),
                "the legacy Consumer variant carries no grant decision: its failure keeps the"
                        + " historical proceed-unclamped behaviour");
    }

    @Test
    void contextWithoutCacheRootIsPassedThroughUnchanged() {
        AtomicReference<SandboxContext> captured = new AtomicReference<>();
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        capturingManager(captured), new SandboxBackedFilesystem());
        SandboxContext original = contextWithProjection(List.of("AGENTS.md", "skills"));
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, original)
                        .build();

        mw.setProjectionAwareBeforeStart(rc -> List.of(".skills-cache/u1/market/alpha"));
        mw.acquireForCall(ctx);

        assertSame(
                original,
                captured.get(),
                "a projection that does not cover .skills-cache has nothing to narrow");
        assertTrue(projectionRoots(original).contains("AGENTS.md"));
    }
}
