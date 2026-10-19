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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@EnabledOnOs(OS.LINUX)
class SkillsCacheProjectionReviewTest {
    @TempDir Path temp;

    @ParameterizedTest
    @EnumSource(IsolationScope.class)
    void scopeEncodingExactlyMatchesRealStaging(IsolationScope scope) throws Exception {
        for (String user : List.of("alice", "bob", "alice@corp", "alice#corp", "_shared")) {
            RuntimeContext ctx =
                    RuntimeContext.builder().userId(user).sessionId(user + "-session").build();
            Path host = temp.resolve("host");
            AgentSkillRepository repository = repository();
            new HarnessSkillMiddleware(
                            List.of(repository),
                            new Toolkit(),
                            null,
                            null,
                            new MarketplaceStager(host),
                            ShellPathPolicy.noShell())
                    .isolationScope(scope)
                    .prestageMarketplaceSkills(ctx);
            String root = MarketplaceStager.cacheScopeRootFor(ctx, scope);
            assertTrue(Files.exists(host.resolve(root + "/market/a/script.sh")));
        }
        if (scope == IsolationScope.USER) {
            RuntimeContext noUser = RuntimeContext.builder().sessionId("fallback").build();
            assertEquals(
                    ".skills-cache/fallback", MarketplaceStager.cacheScopeRootFor(noUser, scope));
        }
    }

    @Test
    void runtimeSandboxScopeOverrideAlsoControlsPreStaging() throws Exception {
        RuntimeContext ctx = runtime("alice", "s-override");
        ctx.put(
                SandboxContext.class,
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build());
        Path host = temp.resolve("host");
        new HarnessSkillMiddleware(
                        List.of(repository()),
                        new Toolkit(),
                        null,
                        null,
                        new MarketplaceStager(host),
                        ShellPathPolicy.noShell())
                .isolationScope(IsolationScope.USER)
                .prestageMarketplaceSkills(ctx);
        assertTrue(Files.exists(host.resolve(".skills-cache/s-override/market/a/script.sh")));
        assertFalse(Files.exists(host.resolve(".skills-cache/alice")));
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "SESSION"})
    void sharedTemplateCleansEachUsersScopeWithoutTransferringForeignCache(IsolationScope scope)
            throws Exception {
        Path host = temp.resolve("host");
        Files.createDirectories(host);
        WorkspaceSpec shared = spec(host, temp.resolve("unused"), List.of(".skills-cache/{scope}"));
        RuntimeContext alice = runtime("alice", "alice-s1");
        RuntimeContext bob = runtime("bob", "bob-s1");
        String aliceRoot = MarketplaceStager.cacheScopeRootFor(alice, scope);
        String bobRoot = MarketplaceStager.cacheScopeRootFor(bob, scope);
        write(host.resolve(aliceRoot + "/market/a/keep.txt"), "alice");
        write(host.resolve(bobRoot + "/market/b/keep.txt"), "bob");
        Path aliceSandbox = temp.resolve("alice-sandbox");
        Path bobSandbox = temp.resolve("bob-sandbox");
        write(aliceSandbox.resolve(aliceRoot + "/market/stale/old.txt"), "stale alice");
        write(bobSandbox.resolve(bobRoot + "/market/stale/old.txt"), "stale bob");
        runBound(shared, alice, scope, aliceSandbox);
        assertFalse(Files.exists(aliceSandbox.resolve(aliceRoot + "/market/stale")));
        assertFalse(Files.exists(aliceSandbox.resolve(bobRoot)));
        assertTrue(Files.exists(bobSandbox.resolve(bobRoot + "/market/stale/old.txt")));
        runBound(shared, bob, scope, bobSandbox);
        assertFalse(Files.exists(bobSandbox.resolve(bobRoot + "/market/stale")));
        assertFalse(Files.exists(bobSandbox.resolve(aliceRoot)));
        assertEquals(
                "alice", Files.readString(aliceSandbox.resolve(aliceRoot + "/market/a/keep.txt")));
        assertEquals(
                List.of(".skills-cache/{scope}"),
                projection(shared).getSyncRoots(),
                "shared configuration must never be mutated");
        RuntimeContext next = runtime("alice", "alice-s2");
        String nextRoot = MarketplaceStager.cacheScopeRootFor(next, scope);
        assertEquals(scope == IsolationScope.USER, nextRoot.equals(aliceRoot));
        write(host.resolve(nextRoot + "/market/a/keep.txt"), "next session");
        Path nextSandbox = temp.resolve("next-sandbox");
        write(nextSandbox.resolve(nextRoot + "/market/stale/old.txt"), "stale");
        runBound(shared, next, scope, nextSandbox);
        assertFalse(Files.exists(nextSandbox.resolve(nextRoot + "/market/stale")));
        assertEquals("bob", Files.readString(bobSandbox.resolve(bobRoot + "/market/b/keep.txt")));
    }

    @Test
    void aStaticAliceRootIsRejectedForBobBeforeClientOrCallbackRuns() throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        WorkspaceSpec spec = spec(host, temp.resolve("sandbox"), List.of(".skills-cache/alice"));
        SandboxManager manager =
                new SandboxManager(
                        mock(SandboxClient.class),
                        mock(SessionSandboxStateStore.class),
                        "agent",
                        key -> SandboxLease.noop());
        AtomicBoolean callback = new AtomicBoolean();
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());
        middleware.setBeforeStartCallback(ctx -> callback.set(true));
        RuntimeContext ctx = runtime("bob", "bob-session");
        ctx.put(
                SandboxContext.class,
                SandboxContext.builder()
                        .workspaceSpec(spec)
                        .isolationScope(IsolationScope.USER)
                        .build());
        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(ctx));
        assertFalse(callback.get());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void callbackAndStopAreInsideTheLeaseButCustomNoopCannotBeProvenMutual(boolean mutual)
            throws Exception {
        Semaphore semaphore = new Semaphore(1);
        CountDownLatch firstCallback = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        CountDownLatch secondCallback = new CountDownLatch(1);
        AtomicBoolean firstReleased = new AtomicBoolean();
        ThreadLocal<AtomicBoolean> leaseClosed = new ThreadLocal<>();
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        SandboxExecutionGuard guard =
                key -> {
                    if (attempts.incrementAndGet() == 2) secondAttempt.countDown();
                    if (mutual) semaphore.acquire();
                    AtomicBoolean closed = new AtomicBoolean();
                    leaseClosed.set(closed);
                    return () -> {
                        closed.set(true);
                        firstReleased.set(true);
                        if (mutual) semaphore.release();
                    };
                };
        @SuppressWarnings("unchecked")
        SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
        when(client.create(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Sandbox sandbox = mock(Sandbox.class);
                            org.mockito.Mockito.doAnswer(
                                            call -> {
                                                assertFalse(
                                                        leaseClosed.get().get(),
                                                        "stop must complete before lease release");
                                                return null;
                                            })
                                    .when(sandbox)
                                    .stop();
                            return sandbox;
                        });
        SandboxManager manager =
                new SandboxManager(client, mock(SessionSandboxStateStore.class), "agent", guard);
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());
        middleware.setBeforeStartCallback(
                ctx -> {
                    assertFalse(
                            leaseClosed.get().get(),
                            "pre-staging must execute inside the acquired lease");
                    if ("one".equals(ctx.getSessionId())) firstCallback.countDown();
                    else secondCallback.countDown();
                });
        RuntimeContext first = runtime("alice", "one");
        RuntimeContext second = runtime("alice", "two");
        SandboxContext context =
                SandboxContext.builder().isolationScope(IsolationScope.USER).build();
        first.put(SandboxContext.class, context);
        second.put(SandboxContext.class, context);
        middleware.acquireForCall(first);
        assertTrue(firstCallback.await(1, TimeUnit.SECONDS));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future =
                    executor.submit(
                            () -> {
                                middleware.acquireForCall(second);
                                middleware.releaseForCall(second);
                            });
            assertTrue(secondAttempt.await(10, TimeUnit.SECONDS));
            if (mutual) {
                assertEquals(
                        1,
                        secondCallback.getCount(),
                        "callback must wait behind a real mutual guard");
                assertFalse(firstReleased.get());
                middleware.releaseForCall(first);
            } else {
                assertTrue(
                        secondCallback.await(10, TimeUnit.SECONDS),
                        "a custom no-op lambda really allows overlap");
                middleware.releaseForCall(first);
            }
            future.get(10, TimeUnit.SECONDS);
            assertEquals(0, secondCallback.getCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void currentStateCanBeReadByAStrictLegacyReaderWithNoSyncField() throws Exception {
        WorkspaceSpec configured = spec(temp, temp, List.of(".skills-cache/{scope}"));
        WorkspaceSpec bound =
                SkillsCacheProjection.bindScope(
                        configured, runtime("alice", "s"), IsolationScope.USER);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(bound);
        assertFalse(json.contains("syncRoots"));
        assertFalse(json.contains("cacheProjectionScope"));
        LegacySpec legacy = mapper.readValue(json, LegacySpec.class);
        assertEquals(List.of(".skills-cache"), legacy.entries.get("projection").includeRoots);
        WorkspaceSpec restored = mapper.readValue(json, WorkspaceSpec.class);
        assertFalse(
                SkillsCacheProjection.enabled(restored),
                "only live application config can enable deletion");
    }

    @Test
    void unknownBackendRefusesDeletionBeforeAnyExecOrHydration() throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        WorkspaceSpec spec = spec(host, temp.resolve("sandbox"), List.of(".skills-cache/alice"));
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        AtomicBoolean executed = new AtomicBoolean();
        Sandbox sandbox =
                new AbstractBaseSandbox(state) {
                    @Override
                    protected ExecResult doExec(RuntimeContext ctx, String command, int timeout) {
                        executed.set(true);
                        throw new AssertionError("unsupported backend must not execute deletion");
                    }

                    @Override
                    protected java.io.InputStream doPersistWorkspace() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    protected void doHydrateWorkspace(java.io.InputStream archive) {
                        executed.set(true);
                        throw new AssertionError("must reject before hydration");
                    }

                    @Override
                    protected void doSetupWorkspace() throws Exception {
                        Files.createDirectories(temp.resolve("sandbox"));
                    }

                    @Override
                    protected void doDestroyWorkspace() {}

                    @Override
                    protected String getWorkspaceRoot() {
                        return temp.resolve("sandbox").toString();
                    }

                    @Override
                    public void shutdown() {}
                };
        assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);
        assertFalse(executed.get());
    }

    public static class LegacySpec {
        public String root;
        public Map<String, LegacyEntry> entries;
        public Map<String, String> environment;
    }

    public static class LegacyEntry {
        public String type;
        public String sourceRoot;
        public List<String> includeRoots;
        public boolean ephemeral;
    }

    private void runBound(
            WorkspaceSpec shared, RuntimeContext context, IsolationScope scope, Path destination)
            throws Exception {
        WorkspaceSpec bound = SkillsCacheProjection.bindScope(shared, context, scope);
        bound.setRoot(destination.toString());
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(bound);
        new Issue3469SandboxProjectionTest.LocalTarSandbox(state, destination).start();
    }

    private static AgentSkillRepository repository() {
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        when(repository.getSource()).thenReturn("market");
        when(repository.getAllSkills())
                .thenReturn(
                        List.of(
                                new AgentSkill(
                                        "a",
                                        "desc",
                                        "content",
                                        Map.of("script.sh", "echo a"),
                                        "market")));
        return repository;
    }

    private static WorkspaceSpec spec(Path host, Path sandbox, List<String> roots) {
        WorkspaceProjectionEntry entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(host.toString());
        entry.setIncludeRoots(List.of(".skills-cache"));
        entry.setSyncRoots(roots);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(sandbox.toString());
        spec.getEntries().put("projection", entry);
        return spec;
    }

    private static WorkspaceProjectionEntry projection(WorkspaceSpec spec) {
        return (WorkspaceProjectionEntry) spec.getEntries().get("projection");
    }

    private static RuntimeContext runtime(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
