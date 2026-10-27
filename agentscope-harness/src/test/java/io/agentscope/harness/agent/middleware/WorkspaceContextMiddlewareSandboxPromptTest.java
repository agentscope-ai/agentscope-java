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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards that the sandbox branch of the workspace paragraph names the actual artifact delivery
 * tool ({@code deliver_artifact}) when one is configured, and never references the non-existent
 * "upload/download tools".
 */
class WorkspaceContextMiddlewareSandboxPromptTest {

    @Test
    void emptyKnowledgeOmitsNavigation(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            String prompt = prompt(wm, false);

            assertFalse(prompt.contains("## Domain Knowledge"));
            assertFalse(prompt.contains("knowledge file catalog"));
            assertTrue(prompt.contains("## Memory Recall"));
        }
    }

    @Test
    void inlineKnowledgeDoesNotPromiseAResourceDirectory(@TempDir Path workspace) {
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, mockSandboxFilesystem()) {
                    @Override
                    public String readKnowledgeMd(RuntimeContext rc) {
                        return "Use the action catalog for supported actions.";
                    }
                }) {
            RuntimeContext rc = RuntimeContext.empty();
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm), rc, "BASE\n");

            assertTrue(prompt.contains("## Domain Knowledge"));
            assertTrue(
                    knowledgeContent(rc).contains("Use the action catalog for supported actions."));
            assertFalse(prompt.contains("Use the action catalog for supported actions."));
            assertFalse(prompt.contains("knowledge file catalog"));
        }
    }

    @Test
    void remoteFileCatalogGuidesReadsWithoutInliningContents(@TempDir Path workspace) {
        AbstractSandboxFilesystem fs = mockSandboxFilesystem();
        when(fs.glob(any(), anyString(), anyString()))
                .thenReturn(
                        GlobResult.success(
                                List.of(FileInfo.ofFile("knowledge/actions.md", 64, ""))));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            RuntimeContext rc = RuntimeContext.empty();
            String knowledgeFileEntry = "- " + Path.of("knowledge", "actions.md");
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm, 1), rc, "BASE\n");

            assertNotNull(prompt);
            assertTrue(prompt.contains("## Domain Knowledge"));
            assertTrue(prompt.contains("knowledge file catalog"));
            assertTrue(knowledgeContent(rc).contains(knowledgeFileEntry));
            assertFalse(prompt.contains(knowledgeFileEntry));
            assertFalse(prompt.contains("holds many detailed reference documents"));
        }
    }

    @Test
    void hostKnowledgeFallbackUsesTheSameCatalog(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/KNOWLEDGE.md"), "Supported flow actions");
        Files.writeString(workspace.resolve("knowledge/actions.md"), "Action details");
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            RuntimeContext rc = RuntimeContext.empty();
            String knowledgeFileEntry = "- " + Path.of("knowledge", "actions.md");
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm), rc, "BASE\n");

            assertTrue(knowledgeContent(rc).contains("Supported flow actions"));
            assertTrue(knowledgeContent(rc).contains(knowledgeFileEntry));
            assertTrue(prompt.contains("knowledge file catalog"));
            assertFalse(knowledgeContent(rc).contains("Action details"));
        }
    }

    @Test
    void failedCatalogDoesNotAdvertiseKnowledge(@TempDir Path workspace) {
        AbstractSandboxFilesystem fs = mockSandboxFilesystem();
        when(fs.glob(any(), anyString(), anyString())).thenReturn(GlobResult.fail("Unavailable"));
        try (WorkspaceManager wm = new WorkspaceManager(workspace, fs)) {
            String prompt = prompt(wm, false);

            assertFalse(prompt.contains("## Domain Knowledge"));
            assertFalse(prompt.contains("knowledge file catalog"));
        }
    }

    private String knowledgeContent(RuntimeContext rc) {
        return rc.get(WorkspaceContextMaterials.class).items().stream()
                .filter(item -> "knowledge".equals(item.kind()))
                .findFirst()
                .orElseThrow()
                .content();
    }

    private AbstractSandboxFilesystem mockSandboxFilesystem() {
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        when(fs.id()).thenReturn("sandbox-test");
        when(fs.read(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(ReadResult.success(new FileData("", "utf-8")));
        when(fs.glob(any(), anyString(), anyString())).thenReturn(GlobResult.success(List.of()));
        return fs;
    }

    private String prompt(WorkspaceManager wm, boolean artifactDeliveryEnabled) {
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);
        mw.setArtifactDeliveryEnabled(artifactDeliveryEnabled);
        return WorkspacePromptTestSupport.render(mw, null, "BASE\n");
    }

    @Test
    void sandboxPrompt_withoutDeliveryTool_admitsNoBoundaryMechanism(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            String prompt = prompt(wm, false);

            assertNotNull(prompt);
            assertFalse(prompt.contains("Sandbox root:"));
            assertFalse(prompt.contains("container id:"));
            assertTrue(prompt.contains("configured execution backend"));
            assertTrue(prompt.contains("No artifact delivery tool is configured"));
            assertTrue(prompt.contains("retrieval work still needed"));
            assertTrue(prompt.contains("Use that backend's shell syntax, installed runtimes"));
            assertTrue(prompt.contains("The host workspace directory is:"));
            assertTrue(prompt.contains("The host JVM temporary directory is:"));
            assertFalse(prompt.contains("Local shell:"));
            assertFalse(prompt.contains("must** call deliver_artifact"));
            assertFalse(prompt.contains("upload/download tools"), () -> prompt);
        }
    }

    @Test
    void sandboxPrompt_withDeliveryTool_namesIt(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            String prompt = prompt(wm, true);

            assertNotNull(prompt);
            assertTrue(prompt.contains("call deliver_artifact"), () -> prompt);
            assertTrue(prompt.contains("do not ask whether to deliver"), () -> prompt);
            assertTrue(prompt.contains("Report any failed delivery as incomplete"));
            assertFalse(prompt.contains("upload/download tools"), () -> prompt);
        }
    }

    @Test
    void sandboxRootComesFromCurrentCallEvenWhenSharedProxyPointsElsewhere(
            @TempDir Path workspace) {
        var proxy = new SandboxBackedFilesystem();
        proxy.setSandbox(sandbox("/sibling"));
        var routed = new RoutedSandboxFilesystem(proxy, Map.of());
        // Read workspace templates locally; the execution backend only supplies environment facts.
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.getWorkspace()).thenReturn(workspace);
        when(wm.getFilesystem()).thenReturn(routed);
        when(wm.readAgentsMd(any())).thenReturn("");
        when(wm.readMemoryMd(any())).thenReturn("");
        when(wm.readKnowledgeMd(any())).thenReturn("");
        var middleware = new WorkspaceContextMiddleware(wm);
        var first = RuntimeContext.empty();
        first.put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox("/first")));
        var second = RuntimeContext.empty();
        second.put(
                SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox("/second")));
        String a = WorkspacePromptTestSupport.render(middleware, first, "BASE");
        String b = WorkspacePromptTestSupport.render(middleware, second, "BASE");
        assertTrue(a.contains("Sandbox root: /first"));
        assertFalse(a.contains("/second"));
        assertFalse(a.contains("/sibling"));
        assertTrue(b.contains("Sandbox root: /second"));
        assertTrue(a.contains("Shell commands use the primary backend"));
        assertTrue(a.contains("The host JVM temporary directory is:"));
        assertFalse(a.contains("project's temporary directory"));
        first.get(SandboxAcquireResult.class).beginRelease();
        String released = WorkspacePromptTestSupport.render(middleware, first, "BASE");
        assertFalse(released.contains("Sandbox root:"));
    }

    private Sandbox sandbox(String root) {
        var spec = new WorkspaceSpec();
        spec.setRoot(root);
        SandboxState state = mock(SandboxState.class);
        when(state.getWorkspaceSpec()).thenReturn(spec);
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.getState()).thenReturn(state);
        return sandbox;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docker/work", "/mnt/k8s/pod workspace"})
    void sandboxPromptPreservesConfiguredRoot(String root, @TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            var rc = RuntimeContext.empty();
            rc.put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox(root)));
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm), rc, "BASE");
            assertTrue(prompt.contains("Sandbox root: " + root + "\n"));
            assertFalse(prompt.contains("Sandbox root: /workspace\n"));
            assertFalse(prompt.contains("Local shell:"));
        }
    }

    @Test
    void userManagedSandboxWithoutStateDoesNotInventRoot(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            var rc = RuntimeContext.empty();
            rc.put(
                    SandboxAcquireResult.class,
                    SandboxAcquireResult.userManaged(mock(Sandbox.class)));
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm), rc, "BASE");
            assertFalse(prompt.contains("Sandbox root:"));
            assertFalse(prompt.contains("container id:"));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    void sandboxWithoutUsableRootDoesNotInventOne(String root, @TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            var rc = RuntimeContext.empty();
            rc.put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox(root)));
            String prompt =
                    WorkspacePromptTestSupport.render(
                            new WorkspaceContextMiddleware(wm), rc, "BASE");
            assertFalse(prompt.contains("Sandbox root:"));
        }
    }

    @Test
    void directLocalShellIsDescribedAsHostExecution(@TempDir Path workspace) {
        try (WorkspaceManager wm =
                new WorkspaceManager(workspace, new LocalFilesystemWithShell(workspace))) {
            String prompt = prompt(wm, false);
            assertTrue(
                    prompt.contains(
                            "Local filesystem root on the host: " + workspace.toAbsolutePath()));
            assertTrue(prompt.contains("filesystem's per-call working directory"));
            String shell =
                    System.getProperty("os.name").toLowerCase().contains("win")
                            ? "cmd.exe /c"
                            : "sh -c";
            assertTrue(prompt.contains("Local shell: " + shell + " on the host."));
            assertFalse(prompt.contains("Sandbox root:"));
            assertFalse(prompt.contains("container"));
        }
    }
}
