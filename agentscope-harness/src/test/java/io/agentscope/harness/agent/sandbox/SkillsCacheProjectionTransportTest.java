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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Large host projection manifests, not repository offboarding or host GC tests. */
@EnabledOnOs(OS.LINUX)
class SkillsCacheProjectionTransportTest {
    static final int FILE_COUNT = 20_000;
    static final String SCOPE = ".skills-cache/alice";
    @TempDir Path temp;

    @Test
    void largeHostManifestRetainsFirstMiddleAndLastFilesAndDeletesOnlyStaleCacheEntries()
            throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        Path workspace = Files.createDirectories(temp.resolve("sandbox"));
        for (int index : List.of(0, FILE_COUNT / 2, FILE_COUNT - 1)) {
            write(workspace.resolve(path(index)), "retain " + index);
        }
        write(workspace.resolve(SCOPE + "/stale/file.txt"), "stale");
        write(workspace.resolve(".skills-cache/bob/private.txt"), "bob");
        write(workspace.resolve("outputs/user.txt"), "user output");
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(host.toString());
        projection.setIncludeRoots(List.of(".skills-cache"));
        projection.setSyncRoots(List.of(SCOPE));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(workspace.toString());
        spec.getEntries().put("projection", projection);
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var sandbox =
                new Issue3469SandboxProjectionTest.LocalTarSandbox(state, workspace) {
                    @Override
                    protected ExecResult doExecWithInput(
                            List<String> command, byte[] input, int timeout) throws Exception {
                        calls.incrementAndGet();
                        assertTrue(
                                command.stream().mapToInt(String::length).sum() < 1024,
                                "argv must be independent of the manifest size");
                        JsonNode plan = readPlan(input);
                        if (plan.get("delete").asBoolean()) {
                            assertTrue(
                                    input.length > 4 * 1024 * 1024,
                                    "manifest must exceed both single-argument and overall argv"
                                            + " limits");
                            assertEquals(FILE_COUNT, plan.get("files").size());
                            for (int index : List.of(0, FILE_COUNT / 2, FILE_COUNT - 1)) {
                                assertEquals(path(index), plan.get("files").get(index).asText());
                            }
                            System.out.println(
                                    "Cache transport: files="
                                            + FILE_COUNT
                                            + ", stdin="
                                            + input.length
                                            + ", argv="
                                            + command.stream().mapToInt(String::length).sum());
                        } else {
                            assertFalse(
                                    plan.has("files"), "validation must not carry a file manifest");
                        }
                        return super.doExecWithInput(command, input, timeout);
                    }
                };
        SkillsCacheProjection.reconcile(sandbox, spec, largePayload(), workspace.toString());
        assertEquals(2, calls.get());
        for (int index : List.of(0, FILE_COUNT / 2, FILE_COUNT - 1)) {
            assertEquals("retain " + index, Files.readString(workspace.resolve(path(index))));
        }
        assertFalse(Files.exists(workspace.resolve(SCOPE + "/stale")));
        assertEquals("bob", Files.readString(workspace.resolve(".skills-cache/bob/private.txt")));
        assertEquals("user output", Files.readString(workspace.resolve("outputs/user.txt")));
    }

    static String path(int index) {
        return SCOPE + "/market/a/" + "resource-".repeat(20) + index + ".txt";
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteScriptOrJsonFrameCannotAuthorizeDeletion(boolean truncateScript)
            throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        Path workspace = Files.createDirectories(temp.resolve("sandbox"));
        write(workspace.resolve(SCOPE + "/stale.txt"), "preserve");
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(host.toString());
        projection.setIncludeRoots(List.of(".skills-cache"));
        projection.setSyncRoots(List.of(SCOPE));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(workspace.toString());
        spec.getEntries().put("projection", projection);
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(spec);
        var sandbox =
                new Issue3469SandboxProjectionTest.LocalTarSandbox(state, workspace) {
                    @Override
                    protected ExecResult doExecWithInput(
                            List<String> command, byte[] input, int timeout) throws Exception {
                        int length = input.length - 1;
                        if (truncateScript) {
                            int headerEnd = 0;
                            while (input[headerEnd] != '\n') headerEnd++;
                            int scriptSize =
                                    Integer.parseInt(
                                            new String(
                                                    input,
                                                    0,
                                                    headerEnd,
                                                    java.nio.charset.StandardCharsets.US_ASCII));
                            length = headerEnd + 1 + scriptSize / 2;
                        }
                        return super.doExecWithInput(
                                command, java.util.Arrays.copyOf(input, length), timeout);
                    }
                };
        assertThrows(
                java.io.IOException.class,
                () ->
                        SkillsCacheProjection.validateDestination(
                                sandbox, spec, workspace.toString()));
        assertEquals("preserve", Files.readString(workspace.resolve(SCOPE + "/stale.txt")));
    }

    static WorkspaceProjectionApplier.ProjectionPayload largePayload() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (int index = 0; index < FILE_COUNT; index++) {
                TarArchiveEntry entry = new TarArchiveEntry(path(index));
                entry.setSize(0);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return new WorkspaceProjectionApplier.ProjectionPayload(
                "large", bytes.toByteArray(), FILE_COUNT);
    }

    static JsonNode readPlan(byte[] input) throws Exception {
        var stream = new ByteArrayInputStream(input);
        StringBuilder header = new StringBuilder();
        int next;
        while ((next = stream.read()) != '\n' && next != -1) header.append((char) next);
        stream.skipNBytes(Integer.parseInt(header.toString()));
        return new ObjectMapper().readTree(stream);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
