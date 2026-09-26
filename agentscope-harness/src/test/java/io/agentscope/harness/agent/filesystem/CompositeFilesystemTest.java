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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link CompositeFilesystem#uploadFiles} faithfully propagates each backend's
 * per-file response (success or failure) instead of wrapping every result in success.
 */
class CompositeFilesystemTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @TempDir Path tmp;

    @Test
    void uploadFiles_propagatesBackendFailure() {
        AbstractFilesystem failingBackend = mock(AbstractFilesystem.class);
        when(failingBackend.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.fail("/notes.md", "disk full")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", failingBackend));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertFalse(r.isSuccess(), "backend failure must propagate to caller");
        assertEquals(
                "/memories/notes.md",
                r.path(),
                "response path must be the caller's view, not the backend-internal path");
        assertEquals("disk full", r.error(), "backend error message must propagate verbatim");
    }

    @Test
    void uploadFiles_propagatesBackendSuccess() {
        AbstractFilesystem store = mock(AbstractFilesystem.class);
        when(store.uploadFiles(any(), anyList()))
                .thenReturn(List.of(FileUploadResponse.success("/notes.md")));

        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", store));

        List<FileUploadResponse> resp =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "/memories/notes.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, resp.size());
        FileUploadResponse r = resp.get(0);
        assertTrue(r.isSuccess());
        assertEquals("/memories/notes.md", r.path());
    }

    /**
     * Regression for the marketplace-install bug: {@code AgentSkillsController} writes via
     * {@code WorkspaceManager.writeUtf8WorkspaceRelative} (which strips leading slashes and
     * uploads under {@code "skills/foo/SKILL.md"}), then immediately reads back through the
     * {@link AbstractFilesystem} contract with a leading slash ({@code "/skills/foo/SKILL.md"}).
     * Both calls must route to the same backend regardless of leading-slash convention; if not,
     * the read falls through to the default store, returns failure, the Mono emits no value,
     * and the frontend chokes on an empty response body.
     */
    @Test
    void routes_normalizeLeadingSlash_betweenWriteAndRead() throws Exception {
        Path workspace = tmp.resolve("workspace");
        Path skillsBacking = tmp.resolve("skills-backing");
        Files.createDirectories(workspace);
        Files.createDirectories(skillsBacking);

        LocalFilesystem defaultBackend = new LocalFilesystem(workspace, false, 10, null);
        LocalFilesystem skillsBackend = new LocalFilesystem(skillsBacking, true, 10, null);

        // Routes registered without a leading slash, matching RemoteFilesystemSpec's convention.
        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("skills/", skillsBackend));

        // Write via the WorkspaceManager-style call (no leading slash).
        List<FileUploadResponse> upload =
                composite.uploadFiles(
                        CTX,
                        List.of(
                                Map.entry(
                                        "skills/foo/SKILL.md",
                                        "hello".getBytes(StandardCharsets.UTF_8))));
        assertTrue(upload.get(0).isSuccess(), "upload should succeed via prefix route");

        // Read via the AbstractFilesystem contract (leading slash) — must hit the same store.
        ReadResult viaSlash = composite.read(CTX, "/skills/foo/SKILL.md", 0, Integer.MAX_VALUE);
        assertTrue(viaSlash.isSuccess(), "leading-slash read must route to skills backend");
        assertEquals("hello", viaSlash.fileData().content());

        // And the no-leading-slash form must keep working too.
        ReadResult viaNoSlash = composite.read(CTX, "skills/foo/SKILL.md", 0, Integer.MAX_VALUE);
        assertTrue(viaNoSlash.isSuccess());
        assertEquals("hello", viaNoSlash.fileData().content());

        // exists() must agree on both conventions.
        assertTrue(composite.exists(CTX, "/skills/foo/SKILL.md"));
        assertTrue(composite.exists(CTX, "skills/foo/SKILL.md"));
    }

    /**
     * Regression for the workspace file-tree bug: the platform's {@code
     * AgentWorkspaceController#tree} (and any other caller listing the whole workspace) invokes
     * {@code glob("**&#47;*", "/")}. Java NIO's {@code PathMatcher} for {@code **&#47;*} requires
     * at least one separator, so root-level exact-file routes (like {@code AGENTS.md} /
     * {@code MEMORY.md} / {@code tools.json}) would never satisfy the recursive matcher alone.
     * The composite must apply a "direct" matcher fallback (stripping the leading {@code **&#47;})
     * so depth-1 entries are surfaced too — otherwise the UI's workspace tree silently drops
     * those files.
     */
    @Test
    void globRecursiveRoot_includesExactFileRoutes() throws Exception {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        // Template files at the workspace root that the exact-file routes overlay.
        Files.writeString(workspace.resolve("AGENTS.md"), "# Agents");
        Files.writeString(workspace.resolve("MEMORY.md"), "# Memory");
        Files.writeString(workspace.resolve("tools.json"), "{}");
        // Plus a prefix-route directory with a child, to ensure both shapes coexist.
        Path skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir.resolve("example"));
        Files.writeString(skillsDir.resolve("example").resolve("SKILL.md"), "skill");

        LocalFilesystem defaultBackend = new LocalFilesystem(workspace, false, 10, null);
        // Exact-file routes are backed by a virtualMode LocalFilesystem rooted at the workspace
        // so `exists("/AGENTS.md")` resolves to `workspace/AGENTS.md`, mirroring
        // {@link
        // io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec#exactFileOverlay}.
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);
        LocalFilesystem skillsRoute = new LocalFilesystem(skillsDir, true, 10, null);

        // Preserve declaration order so exact-file routes ("AGENTS.md", ...) sort ahead of the
        // prefix route in the iteration when relevant.
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("AGENTS.md", workspaceTemplate);
        routes.put("MEMORY.md", workspaceTemplate);
        routes.put("tools.json", workspaceTemplate);
        routes.put("skills/", skillsRoute);
        CompositeFilesystem composite = new CompositeFilesystem(defaultBackend, routes);

        GlobResult gr = composite.glob(CTX, "**/*", "/");
        assertTrue(gr.isSuccess(), "recursive root glob must succeed");
        List<String> paths = gr.matches().stream().map(FileInfo::path).collect(Collectors.toList());

        // The root-level exact-file routes must appear in the tree — this is what the
        // workspace-tree UI surfaces and what regressed before the fix.
        assertTrue(paths.contains("AGENTS.md"), "AGENTS.md missing from glob result: " + paths);
        assertTrue(paths.contains("MEMORY.md"), "MEMORY.md missing from glob result: " + paths);
        assertTrue(paths.contains("tools.json"), "tools.json missing from glob result: " + paths);
        // Prefix-route content must keep working.
        assertTrue(
                paths.stream().anyMatch(p -> p.endsWith("skills/example/SKILL.md")),
                "prefix-route child missing: " + paths);

        // Sanity-check that a more selective glob still filters correctly — root-level *.json
        // matches tools.json but not AGENTS.md.
        GlobResult jsonGlob = composite.glob(CTX, "**/*.json", "/");
        List<String> jsonPaths =
                jsonGlob.matches().stream().map(FileInfo::path).collect(Collectors.toList());
        assertTrue(jsonPaths.contains("tools.json"));
        assertFalse(
                jsonPaths.contains("AGENTS.md"), "AGENTS.md must not match *.json: " + jsonPaths);
    }

    /**
     * Root scans must forward the {@link AbstractFilesystem} contract spelling — {@code "/"} —
     * to the default backend, whatever that backend is. {@code "."} and {@code ""} are
     * LocalFilesystem-relative conventions: a sandbox default backend (RoutedSandboxFilesystem
     * installs its primary as the default) shell-expands them against the command cwd, so
     * entries come back as {@code ./name} instead of {@code /name}, and {@code ""} additionally
     * strips the per-user namespace on namespaced local backends. The composite is
     * backend-agnostic and may only send documented spellings across the seam; each backend
     * owns the meaning of its own root (#3253).
     */
    @Test
    void rootScans_forwardContractSpellingToNonLocalDefaultBackend() {
        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);
        when(defaultBackend.ls(any(), any())).thenReturn(LsResult.success(List.of()));
        when(defaultBackend.grep(any(), any(), any(), any()))
                .thenReturn(GrepResult.success(List.of()));
        when(defaultBackend.glob(any(), any(), any())).thenReturn(GlobResult.success(List.of()));

        CompositeFilesystem composite = new CompositeFilesystem(defaultBackend, Map.of());

        // Every root spelling — "/", ".", null, "", whitespace — canonicalizes to the
        // contract spelling; blank must not slip through to the backend's raw cwd (#3253).
        composite.ls(CTX, "/");
        composite.ls(CTX, ".");
        composite.ls(CTX, null);
        composite.ls(CTX, "");
        composite.ls(CTX, "   ");
        verify(defaultBackend, times(5)).ls(any(), eq("/"));

        // "/" and "." canonicalize to the contract spelling; null is grep's documented
        // working-directory form and is forwarded as-is for the backend to interpret.
        composite.grep(CTX, "needle", "/", null);
        composite.grep(CTX, "needle", ".", null);
        composite.grep(CTX, "needle", "", null);
        composite.grep(CTX, "needle", "   ", null);
        verify(defaultBackend, times(4)).grep(any(), eq("needle"), eq("/"), isNull());
        composite.grep(CTX, "needle", null, null);
        verify(defaultBackend).grep(any(), eq("needle"), isNull(), isNull());

        composite.glob(CTX, "**/*.md", "/");
        composite.glob(CTX, "**/*.md", ".");
        composite.glob(CTX, "**/*.md", null);
        composite.glob(CTX, "**/*.md", "");
        verify(defaultBackend, times(4)).glob(any(), eq("**/*.md"), eq("/"));
    }

    /**
     * Review follow-up (Info): with a configured {@code "/"} route, {@code ls(null)} and
     * {@code grep(null)} must both take root aggregation — the default backend plus the
     * route's entries — instead of ls letting {@code routeForPath(null)} capture the route
     * while grep skips routing. The two enumeration surfaces must stay symmetric for every
     * root spelling that does not explicitly name the route.
     */
    @Test
    void nullRootScan_aggregatesDefaultAndRootRoute_symmetricWithGrep() {
        AbstractFilesystem defaultBackend = mock(AbstractFilesystem.class);
        when(defaultBackend.ls(any(), any()))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("default-file.md", 1L, ""))));
        when(defaultBackend.grep(any(), any(), any(), any()))
                .thenReturn(GrepResult.success(List.of()));

        AbstractFilesystem rootRouteBackend = mock(AbstractFilesystem.class);
        when(rootRouteBackend.ls(any(), any()))
                .thenReturn(LsResult.success(List.of(FileInfo.ofFile("routed-file.md", 1L, ""))));
        when(rootRouteBackend.grep(any(), any(), any(), any()))
                .thenReturn(GrepResult.success(List.of()));

        CompositeFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/", rootRouteBackend));

        // ls(null): default entry + the "/" route entry, never the route alone.
        LsResult ls = composite.ls(CTX, null);
        assertTrue(ls.isSuccess(), () -> "ls(null) failed: " + ls.error());
        List<String> paths = ls.entries().stream().map(FileInfo::path).sorted().toList();
        assertTrue(
                paths.contains("default-file.md"),
                "ls(null) must aggregate the default backend: " + paths);
        assertTrue(paths.contains("/"), "ls(null) must surface the '/' route entry: " + paths);
        verify(defaultBackend).ls(any(), eq("/"));
        verify(rootRouteBackend, org.mockito.Mockito.never()).ls(any(), any());

        // grep(null): same aggregation shape — default backend root scan plus the route.
        GrepResult grep = composite.grep(CTX, "needle", null, null);
        assertTrue(grep.isSuccess(), () -> "grep(null) failed: " + grep.error());
        verify(defaultBackend).grep(any(), eq("needle"), isNull(), isNull());
        verify(rootRouteBackend).grep(any(), eq("needle"), eq("/"), isNull());
    }
}
