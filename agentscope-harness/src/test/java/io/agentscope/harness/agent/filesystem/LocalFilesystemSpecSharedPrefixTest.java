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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.util.SharedPrefixUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemSpecSharedPrefixTest {

    @TempDir Path tempDir;

    @Test
    void sharedPrefixesAreNormalizedOrderedAndImmutable() {
        LocalFilesystemSpec spec =
                new LocalFilesystemSpec()
                        .addSharedPrefix(null)
                        .addSharedPrefix(" ")
                        .addSharedPrefix("old")
                        .sharedPrefixes(Arrays.asList(" docs\\ ", "", null, "knowledge/", "docs"));

        assertEquals(List.of("docs/", "knowledge/"), new ArrayList<>(spec.getSharedPrefixes()));
        assertThrows(
                UnsupportedOperationException.class, () -> spec.getSharedPrefixes().add("other/"));

        spec.sharedPrefixes(null);
        assertTrue(spec.getSharedPrefixes().isEmpty());
    }

    @Test
    void sharedPrefixNormalizationHandlesSeparatorsAndRejectsUnsafeSegments() {
        assertEquals(
                "docs/reference/",
                SharedPrefixUtils.normalizeDirectoryPrefix(" ///docs\\reference/// "));

        assertThrows(
                IllegalArgumentException.class,
                () -> SharedPrefixUtils.normalizeDirectoryPrefix("////"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SharedPrefixUtils.normalizeDirectoryPrefix("docs//private"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SharedPrefixUtils.normalizeDirectoryPrefix("./docs"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SharedPrefixUtils.normalizeDirectoryPrefix("docs/../private"));
    }

    @Test
    void sharedPrefixIsVisibleToEveryUserAndKeepsShellCapability() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path project = tempDir.resolve("project");
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(project);
        Files.writeString(workspace.resolve("docs/guide.md"), "shared");

        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .isolationScope(IsolationScope.USER)
                        .addSharedPrefix("docs")
                        .toFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();

        assertTrue(fs instanceof AbstractSandboxFilesystem, "local shell capability must survive");
        assertEquals("shared", fs.read(alice, "docs/guide.md", 0, 0).fileData().content());
        assertEquals("shared", fs.read(bob, "docs/guide.md", 0, 0).fileData().content());
        assertTrue(
                fs.glob(bob, "**/*", "/").matches().stream()
                        .anyMatch(file -> "docs/guide.md".equals(file.path())));

        assertTrue(fs.write(alice, "docs/guide.md", "alice override").isSuccess());
        assertEquals("alice override", fs.read(alice, "docs/guide.md", 0, 0).fileData().content());
        assertEquals("shared", fs.read(bob, "docs/guide.md", 0, 0).fileData().content());
        assertEquals("shared", Files.readString(workspace.resolve("docs/guide.md")));
        assertEquals("alice override", Files.readString(workspace.resolve("alice/docs/guide.md")));
    }

    @Test
    void agentScopeKeepsWritableOverlaySeparateFromSharedSource() throws Exception {
        Path workspace = tempDir.resolve("agent-workspace");
        Path project = tempDir.resolve("agent-project");
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(project);
        Files.writeString(workspace.resolve("docs/guide.md"), "shared");

        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .isolationScope(IsolationScope.AGENT)
                        .addSharedPrefix("docs/")
                        .toFilesystem(workspace, IsolationScope.AGENT.toNamespaceFactory());
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();

        assertTrue(fs.write(alice, "docs/guide.md", "agent override").isSuccess());
        assertEquals("agent override", fs.read(bob, "docs/guide.md", 0, 0).fileData().content());
        assertEquals("shared", Files.readString(workspace.resolve("docs/guide.md")));
        assertEquals(
                "agent override",
                Files.readString(workspace.resolve(".shared-overrides/docs/guide.md")));
    }

    @Test
    void sharedRouteSupportsListingSearchingAndShellDelegationWithProjectWritable()
            throws Exception {
        Path workspace = tempDir.resolve("routed-workspace");
        Path project = tempDir.resolve("routed-project");
        Files.createDirectories(workspace.resolve("docs/nested"));
        Files.createDirectories(project);
        Files.writeString(workspace.resolve("docs/guide.md"), "shared guide");
        Files.writeString(workspace.resolve("docs/nested/notes.md"), "shared notes");

        LocalFilesystemSpec spec =
                new LocalFilesystemSpec()
                        .project(project)
                        .projectWritable(true)
                        .addSharedPrefix("docs");
        AbstractFilesystem fs = spec.toFilesystem(workspace, null);
        RuntimeContext context = RuntimeContext.empty();

        assertTrue(spec.isProjectWritable());

        LsResult listing = fs.ls(context, "docs");
        assertTrue(listing.isSuccess());
        assertEquals(
                List.of("docs/guide.md", "docs/nested/"),
                listing.entries().stream().map(file -> file.path()).toList());

        GrepResult grep = fs.grep(context, "shared", "docs", "*.md");
        assertTrue(grep.isSuccess());
        assertTrue(grep.matches().stream().anyMatch(match -> "docs/guide.md".equals(match.path())));
        assertTrue(
                grep.matches().stream()
                        .anyMatch(match -> "docs/nested/notes.md".equals(match.path())));

        GlobResult glob = fs.glob(context, "**/*", "docs");
        assertTrue(glob.isSuccess());
        assertTrue(glob.matches().stream().anyMatch(file -> "docs/guide.md".equals(file.path())));

        AbstractSandboxFilesystem shell = (AbstractSandboxFilesystem) fs;
        assertTrue(shell.id().startsWith("local-"));
        assertEquals(1, shell.execute(context, " ", null).exitCode());
    }
}
