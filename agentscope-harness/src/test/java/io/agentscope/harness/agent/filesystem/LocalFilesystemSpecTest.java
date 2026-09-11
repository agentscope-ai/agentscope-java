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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemSpecTest {

    private static final RuntimeContext USER_CONTEXT =
            RuntimeContext.builder().userId("user-1").build();

    private static final NamespaceFactory USER_NAMESPACE =
            rc -> rc != null && rc.getUserId() != null ? List.of(rc.getUserId()) : List.of();

    @Test
    void sharedKnowledgeIsReadableFromUserNamespace(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path knowledge = workspace.resolve("knowledge");
        Files.createDirectories(knowledge);
        Files.writeString(
                knowledge.resolve("shared.md"), "shared knowledge", StandardCharsets.UTF_8);

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, USER_NAMESPACE);

        ReadResult result = filesystem.read(USER_CONTEXT, "knowledge/shared.md", 0, 0);

        assertTrue(result.isSuccess(), () -> "expected shared fallback, got: " + result.error());
        assertEquals("shared knowledge", result.fileData().content());
    }

    @Test
    void userKnowledgeOverridesSharedAndDirectoryEntriesAreMerged(
            @TempDir Path workspace, @TempDir Path project) throws IOException {
        Path sharedKnowledge = workspace.resolve("knowledge");
        Path userKnowledge = workspace.resolve("user-1").resolve("knowledge");
        Files.createDirectories(sharedKnowledge);
        Files.createDirectories(userKnowledge);
        Files.writeString(
                sharedKnowledge.resolve("same.md"), "shared version", StandardCharsets.UTF_8);
        Files.writeString(
                sharedKnowledge.resolve("shared-only.md"), "shared only", StandardCharsets.UTF_8);
        Files.writeString(userKnowledge.resolve("same.md"), "user version", StandardCharsets.UTF_8);
        Files.writeString(
                userKnowledge.resolve("user-only.md"), "user only", StandardCharsets.UTF_8);

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, USER_NAMESPACE);

        ReadResult result = filesystem.read(USER_CONTEXT, "knowledge/same.md", 0, 0);
        LsResult listing = filesystem.ls(USER_CONTEXT, "knowledge");

        assertTrue(result.isSuccess());
        assertEquals("user version", result.fileData().content());
        assertTrue(listing.isSuccess());
        assertEquals(3, listing.entries().size());
        assertEquals(
                List.of("knowledge/same.md", "knowledge/shared-only.md", "knowledge/user-only.md"),
                listing.entries().stream().map(info -> info.path()).sorted().toList());
    }

    @Test
    void runtimeDataDoesNotFallBackToSharedWorkspace(@TempDir Path workspace, @TempDir Path project)
            throws IOException {
        Path memory = workspace.resolve("memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("private.md"), "shared secret", StandardCharsets.UTF_8);

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, USER_NAMESPACE);

        ReadResult result = filesystem.read(USER_CONTEXT, "memory/private.md", 0, 0);

        assertFalse(result.isSuccess(), "runtime data must remain isolated by namespace");
    }

    @Test
    void staticAssetsPreferSharedWorkspaceAndRetainProjectFallback(
            @TempDir Path workspace, @TempDir Path project) throws IOException {
        Files.writeString(workspace.resolve("AGENTS.md"), "shared agents", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("AGENTS.md"), "project agents", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("tools.json"), "project tools", StandardCharsets.UTF_8);
        Path projectKnowledge = project.resolve("knowledge");
        Files.createDirectories(projectKnowledge);
        Files.writeString(
                projectKnowledge.resolve("default.md"),
                "project knowledge",
                StandardCharsets.UTF_8);

        AbstractFilesystem filesystem =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, USER_NAMESPACE);

        ReadResult agents = filesystem.read(USER_CONTEXT, "AGENTS.md", 0, 0);
        ReadResult tools = filesystem.read(USER_CONTEXT, "tools.json", 0, 0);
        ReadResult knowledge = filesystem.read(USER_CONTEXT, "knowledge/default.md", 0, 0);

        assertTrue(agents.isSuccess());
        assertTrue(tools.isSuccess());
        assertTrue(knowledge.isSuccess());
        assertEquals("shared agents", agents.fileData().content());
        assertEquals("project tools", tools.fileData().content());
        assertEquals("project knowledge", knowledge.fileData().content());
    }
}
