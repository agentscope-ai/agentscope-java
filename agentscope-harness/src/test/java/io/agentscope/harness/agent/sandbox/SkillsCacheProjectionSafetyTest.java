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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs(OS.LINUX)
class SkillsCacheProjectionSafetyTest {

    private static final String SCOPE = ".skills-cache/alice";
    @TempDir Path temp;

    @Test
    void defaultProjectionRetainsOverlaySemantics() throws Exception {
        var sandbox = fixture(List.of());
        write(sandboxRoot().resolve(SCOPE + "/market/b/script.sh"), "legacy cache");
        sandbox.start();
        assertEquals(
                "legacy cache",
                Files.readString(sandboxRoot().resolve(SCOPE + "/market/b/script.sh")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commandFailureDoesNotRecordSuccessOrHydrateOverTheCache(boolean failDeletion)
            throws Exception {
        var original = fixture(List.of(SCOPE));
        var failing =
                new Issue3469SandboxProjectionTest.LocalTarSandbox(
                        original.getState(), sandboxRoot()) {
                    @Override
                    protected ExecResult doExecWithInput(
                            List<String> command, byte[] input, int timeoutSeconds)
                            throws Exception {
                        boolean deletion =
                                SkillsCacheProjectionTransportTest.readPlan(input)
                                        .get("delete")
                                        .asBoolean();
                        if (!failDeletion || deletion) {
                            return new ExecResult(127, "", "backend command unavailable", false);
                        }
                        return super.doExecWithInput(command, input, timeoutSeconds);
                    }
                };
        assertStartFailsWithoutChangingCache(failing);
        assertFalse(Files.exists(sandboxRoot().resolve(SCOPE + "/market/a/SKILL.md")));
    }

    @Test
    void removesOnlyMissingEntriesInTheOwnedScopeAndPreservesLiteralFilenames() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        write(host().resolve(SCOPE + "/market/a/[x]' file.txt"), "literal filename");
        write(sandboxRoot().resolve(SCOPE + "/market/a/[x]' file.txt"), "old literal filename");
        write(sandboxRoot().resolve(SCOPE + "/market/a/x' file.txt"), "orphan");
        write(sandboxRoot().resolve(SCOPE + "/market/b/script.sh"), "orphan");
        List<String> untouched =
                List.of(
                        ".skills-cache/bob/market/private.txt",
                        ".skills-cache-old/user.txt",
                        "skills/user.txt",
                        "knowledge/user.txt",
                        "AGENTS.md",
                        "outputs/user.txt");
        for (String path : untouched) write(sandboxRoot().resolve(path), "user data");
        sandbox.start();
        assertFalse(Files.exists(sandboxRoot().resolve(SCOPE + "/market/b")));
        assertFalse(Files.exists(sandboxRoot().resolve(SCOPE + "/market/a/x' file.txt")));
        assertEquals(
                "literal filename",
                Files.readString(sandboxRoot().resolve(SCOPE + "/market/a/[x]' file.txt")));
        for (String path : untouched)
            assertEquals("user data", Files.readString(sandboxRoot().resolve(path)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".skills-cache",
                "skills",
                "knowledge",
                "AGENTS.md",
                "../.skills-cache/alice",
                "/.skills-cache/alice",
                ".skills-cache-old/alice",
                ".skills-cache/alice/../bob",
                ".skills-cache/alice/",
                ".skills-cache//alice",
                ".skills-cache/alice\n",
                ".skills-cache\\alice"
            })
    void rejectsUnsafeOwnershipBeforeDeletingAnything(String root) throws Exception {
        var sandbox = fixture(List.of(root));
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @Test
    void missingHostWorkspaceIsNotAnAuthoritativeEmptyCache() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        projection(sandbox).setSourceRoot(temp.resolve("missing-host").toString());
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @Test
    void oldSnapshotWithAnAlreadyMatchingHostHashStillLosesItsStaleSkill() throws Exception {
        var initial = fixture(List.of(SCOPE));
        var snapshot = new LocalSnapshotSpec(temp.resolve("snapshots")).build("legacy");
        initial.getState().setSnapshot(snapshot);
        write(host().resolve(SCOPE + "/market/b/script.sh"), "stale skill");
        initial.start();
        initial.stop();
        assertTrue(snapshot.isRestorable());
        Files.delete(host().resolve(SCOPE + "/market/b/script.sh"));
        Files.delete(host().resolve(SCOPE + "/market/b"));
        WorkspaceSpec spec = initial.getState().getWorkspaceSpec().copy();
        Path restoredRoot = temp.resolve("restored");
        spec.setRoot(restoredRoot.toString());
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        state.setSnapshot(snapshot);
        state.setWorkspaceProjectionHash(WorkspaceProjectionApplier.build(spec).hash());
        var restored = new Issue3469SandboxProjectionTest.LocalTarSandbox(state, restoredRoot);
        restored.start();
        assertFalse(Files.exists(restoredRoot.resolve(SCOPE + "/market/b")));
        assertEquals(
                "skill a", Files.readString(restoredRoot.resolve(SCOPE + "/market/a/SKILL.md")));
    }

    @Test
    void rejectsEscapingIncludeRoot() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        projection(sandbox).setIncludeRoots(List.of("../.skills-cache"));
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @Test
    void rejectsCacheOutsideTheIncludedProjection() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        projection(sandbox).setIncludeRoots(List.of("skills"));
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @Test
    void refusesToReconcileThroughABindMount() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        sandbox.getState()
                .getWorkspaceSpec()
                .getEntries()
                .put(SCOPE + "/market/b", new BindMountEntry());
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @Test
    void rejectsConflictingProjectionSources() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        Path otherHost = Files.createDirectories(temp.resolve("other-host"));
        WorkspaceProjectionEntry other = new WorkspaceProjectionEntry();
        other.setSourceRoot(otherHost.toString());
        other.setIncludeRoots(List.of(".skills-cache"));
        sandbox.getState().getWorkspaceSpec().getEntries().put("other", other);
        assertStartFailsWithoutChangingCache(sandbox);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".skills-cache",
                ".skills-cache/alice",
                ".skills-cache/alice/market/a/linked"
            })
    void rejectsHostDirectoryLinksIncludingWindowsJunctions(String relative) throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        Path link = host().resolve(relative);
        if (Files.exists(link)) {
            // Existing fixture ancestors must remain; place the alias in a separate host instead.
            Path aliasHost = Files.createDirectories(temp.resolve("alias-host"));
            projection(sandbox).setSourceRoot(aliasHost.toString());
            link = aliasHost.resolve(relative);
        }
        Path outside = Files.createDirectories(temp.resolve("outside-host"));
        write(outside.resolve("user.txt"), "outside data");
        directoryLink(link, outside);
        assertStartFailsWithoutChangingCache(sandbox);
        assertEquals("outside data", Files.readString(outside.resolve("user.txt")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".skills-cache",
                ".skills-cache/alice",
                ".skills-cache/alice/market/a/linked"
            })
    void refusesSandboxDirectoryLinksWithoutTouchingTheTarget(String relative) throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        Path outside = Files.createDirectories(temp.resolve("outside-sandbox"));
        write(outside.resolve("user.txt"), "outside data");
        directoryLink(sandboxRoot().resolve(relative), outside);
        assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);
        assertEquals("previous hash", sandbox.getState().getWorkspaceProjectionHash());
        assertEquals("outside data", Files.readString(outside.resolve("user.txt")));
    }

    @Test
    void oldEntryJsonDefaultsToOverlayAndRuntimeConfigurationIsNeverPersisted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceProjectionEntry entry =
                (WorkspaceProjectionEntry)
                        mapper.readValue(
                                "{\"type\":\"workspace_projection\",\"sourceRoot\":\"/host\",\"includeRoots\":[\".skills-cache\"]}",
                                WorkspaceEntry.class);
        assertTrue(entry.getSyncRoots().isEmpty());
        assertFalse(mapper.writeValueAsString(entry).contains("syncRoots"));
        entry.setSyncRoots(List.of(SCOPE));
        assertFalse(mapper.writeValueAsString(entry).contains("syncRoots"));
        WorkspaceProjectionEntry roundTrip =
                (WorkspaceProjectionEntry)
                        mapper.readValue(mapper.writeValueAsString(entry), WorkspaceEntry.class);
        assertTrue(roundTrip.getSyncRoots().isEmpty());
        WorkspaceProjectionEntry stageThree =
                (WorkspaceProjectionEntry)
                        mapper.readValue(
                                "{\"type\":\"workspace_projection\",\"syncRoots\":[\".skills-cache/alice\"]}",
                                WorkspaceEntry.class);
        assertTrue(
                stageThree.getSyncRoots().isEmpty(),
                "historical local fields cannot authorize deletion");
    }

    @Test
    void builderAddsSyncRootsOnlyWhenExplicitlyConfigured() {
        DockerFilesystemSpec legacy = new DockerFilesystemSpec();
        WorkspaceProjectionEntry original =
                (WorkspaceProjectionEntry)
                        legacy.toSandboxContext(temp)
                                .getWorkspaceSpec()
                                .getEntries()
                                .get("__workspace_projection__");
        assertTrue(original.getSyncRoots().isEmpty());
        legacy.workspaceProjectionSyncRoots(List.of(SCOPE));
        WorkspaceProjectionEntry configured =
                (WorkspaceProjectionEntry)
                        legacy.toSandboxContext(temp)
                                .getWorkspaceSpec()
                                .getEntries()
                                .get("__workspace_projection__");
        assertEquals(List.of(SCOPE), configured.getSyncRoots());
        legacy.workspaceProjectionEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> legacy.toSandboxContext(temp));
    }

    @Test
    void unguardedManagedCallsAreRejected() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        SandboxManager manager =
                new SandboxManager(
                        mock(SandboxClient.class), mock(SessionSandboxStateStore.class), "agent");
        assertThrows(
                IllegalArgumentException.class, () -> manager.acquire(context(sandbox), runtime()));
    }

    @Test
    void externalSandboxAndExternalStateCannotBypassTheGuardRequirement() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        SandboxManager manager =
                new SandboxManager(
                        mock(SandboxClient.class),
                        mock(SessionSandboxStateStore.class),
                        "agent",
                        key -> SandboxLease.noop());
        WorkspaceSpec spec = sandbox.getState().getWorkspaceSpec();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.acquire(
                                SandboxContext.builder()
                                        .workspaceSpec(spec)
                                        .externalSandbox(sandbox)
                                        .build(),
                                runtime()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.acquire(
                                SandboxContext.builder().externalSandbox(sandbox).build(),
                                runtime()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.acquire(
                                SandboxContext.builder()
                                        .externalSandboxState(sandbox.getState())
                                        .build(),
                                runtime()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        manager.acquire(
                                SandboxContext.builder()
                                        .workspaceSpec(spec)
                                        .externalSandboxState(sandbox.getState())
                                        .build(),
                                runtime()));
        assertThrows(
                IllegalArgumentException.class,
                () -> manager.acquire(context(sandbox), RuntimeContext.empty()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameScopeCannotReconcileUntilTheActiveCallReleasesItsLease() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        write(host().resolve(SCOPE + "/market/b/script.sh"), "active skill");
        SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        Semaphore semaphore = new Semaphore(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        SandboxExecutionGuard guard =
                key -> {
                    if (attempts.incrementAndGet() == 2) secondAttempt.countDown();
                    semaphore.acquire();
                    AtomicBoolean released = new AtomicBoolean();
                    return () -> {
                        if (released.compareAndSet(false, true)) semaphore.release();
                    };
                };
        SandboxManager manager =
                new SandboxManager(client, mock(SessionSandboxStateStore.class), "agent", guard);
        SandboxAcquireResult first = manager.acquire(context(sandbox), runtime());
        var executor = Executors.newSingleThreadExecutor();
        try {
            first.getSandbox().start();
            Files.delete(host().resolve(SCOPE + "/market/b/script.sh"));
            Files.delete(host().resolve(SCOPE + "/market/b"));
            var second =
                    executor.submit(
                            () -> {
                                SandboxAcquireResult acquired =
                                        manager.acquire(context(sandbox), runtime());
                                try {
                                    acquired.getSandbox().start();
                                    return Files.notExists(
                                            sandboxRoot().resolve(SCOPE + "/market/b"));
                                } finally {
                                    manager.release(acquired);
                                    acquired.getLease().close();
                                }
                            });
            assertTrue(secondAttempt.await(10, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            assertTrue(
                    Files.exists(sandboxRoot().resolve(SCOPE + "/market/b/script.sh")),
                    "the active call's skill must remain until its lease is released");
            manager.release(first);
            first.getLease().close();
            assertTrue(second.get(20, TimeUnit.SECONDS));
        } finally {
            first.getLease().close();
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedSyncConfigurationCannotFallBackToFreshCreateWhenItsGuardIsMissing() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
        SessionSandboxStateStore store = mock(SessionSandboxStateStore.class);
        when(store.load(any())).thenReturn(Optional.of("saved"));
        when(client.deserializeState("saved", null)).thenReturn(sandbox.getState());
        SandboxManager manager = new SandboxManager(client, store, "agent");
        SandboxContext context =
                SandboxContext.builder().isolationScope(IsolationScope.USER).build();
        assertThrows(IllegalArgumentException.class, () -> manager.acquire(context, runtime()));
        verify(client, never()).resume(any());
        verify(client, never()).create(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentOverlayConfigurationCanDisableSynchronizationInSavedState() throws Exception {
        var sandbox = fixture(List.of(SCOPE));
        SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
        SessionSandboxStateStore store = mock(SessionSandboxStateStore.class);
        when(store.load(any())).thenReturn(Optional.of("saved"));
        when(client.deserializeState("saved", null)).thenReturn(sandbox.getState());
        when(client.resume(sandbox.getState())).thenReturn(sandbox);
        SandboxManager manager = new SandboxManager(client, store, "agent");
        WorkspaceSpec overlay = sandbox.getState().getWorkspaceSpec().copy();
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(host().toString());
        projection.setIncludeRoots(List.of(".skills-cache"));
        overlay.getEntries().put("__workspace_projection__", projection);
        SandboxContext context =
                SandboxContext.builder()
                        .workspaceSpec(overlay)
                        .isolationScope(IsolationScope.USER)
                        .build();
        var acquired = manager.acquire(context, runtime());
        assertFalse(
                SkillsCacheProjection.enabled(acquired.getSandbox().getState().getWorkspaceSpec()));
        acquired.getLease().close();
    }

    private Issue3469SandboxProjectionTest.LocalTarSandbox fixture(List<String> syncRoots)
            throws Exception {
        write(host().resolve(SCOPE + "/market/a/SKILL.md"), "skill a");
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(host().toString());
        projection.setIncludeRoots(List.of(".skills-cache"));
        projection.setSyncRoots(syncRoots);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(sandboxRoot().toString());
        spec.getEntries().put("__workspace_projection__", projection);
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        state.setWorkspaceProjectionHash("previous hash");
        return new Issue3469SandboxProjectionTest.LocalTarSandbox(state, sandboxRoot());
    }

    private void assertStartFailsWithoutChangingCache(
            Issue3469SandboxProjectionTest.LocalTarSandbox sandbox) throws Exception {
        Path file = sandboxRoot().resolve(SCOPE + "/market/b/script.sh");
        write(file, "preserved cache");
        assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);
        assertEquals("preserved cache", Files.readString(file));
        assertEquals("previous hash", sandbox.getState().getWorkspaceProjectionHash());
    }

    private static WorkspaceProjectionEntry projection(Sandbox sandbox) {
        return (WorkspaceProjectionEntry)
                sandbox.getState().getWorkspaceSpec().getEntries().get("__workspace_projection__");
    }

    private static SandboxContext context(Sandbox sandbox) {
        return SandboxContext.builder()
                .workspaceSpec(sandbox.getState().getWorkspaceSpec())
                .isolationScope(IsolationScope.USER)
                .build();
    }

    private static RuntimeContext runtime() {
        return RuntimeContext.builder().userId("alice").sessionId("session").build();
    }

    private Path host() {
        return temp.resolve("host");
    }

    private Path sandboxRoot() {
        return temp.resolve("sandbox");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void directoryLink(Path link, Path target) throws Exception {
        Files.createDirectories(link.getParent());
        if (java.io.File.separatorChar == '\\') {
            Process process =
                    new ProcessBuilder(
                                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                            .redirectErrorStream(true)
                            .start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
