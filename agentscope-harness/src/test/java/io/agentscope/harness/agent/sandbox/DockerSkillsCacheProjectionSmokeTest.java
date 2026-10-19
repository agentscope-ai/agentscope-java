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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "agentscope.issue3469.dockerSmoke", matches = "true")
class DockerSkillsCacheProjectionSmokeTest {
    @TempDir Path temp;

    @Test
    void realDockerSnapshotRemovesFilesAbsentFromHostProjectionAndPreservesUserOutput()
            throws Exception {
        Path host = temp.resolve("host");
        write(host.resolve(".skills-cache/alice/market/a/file.txt"), "a");
        write(host.resolve(".skills-cache/alice/market/b/file.txt"), "b");
        DockerSandboxClient client = new DockerSandboxClient();
        Sandbox first =
                client.create(
                        spec(host), new LocalSnapshotSpec(temp.resolve("snapshots")), options());
        Sandbox second = null;
        try {
            first.start();
            assertEquals(
                    "b",
                    first.exec(null, "cat /workspace/.skills-cache/alice/market/b/file.txt", 10)
                            .stdout());
            assertTrue(
                    first.exec(
                                    null,
                                    "mkdir -p /workspace/outputs; printf user-output >"
                                            + " /workspace/outputs/result.txt",
                                    10)
                            .ok());
            first.stop();
            first.shutdown();
            Files.delete(host.resolve(".skills-cache/alice/market/b/file.txt"));
            Files.delete(host.resolve(".skills-cache/alice/market/b"));
            second = client.resume(first.getState());
            second.start();
            assertEquals(
                    "a",
                    second.exec(null, "cat /workspace/.skills-cache/alice/market/a/file.txt", 10)
                            .stdout());
            assertTrue(
                    second.exec(null, "test ! -e /workspace/.skills-cache/alice/market/b", 10)
                            .ok());
            assertEquals(
                    "user-output",
                    second.exec(null, "cat /workspace/outputs/result.txt", 10).stdout());
        } finally {
            if (second != null) second.shutdown();
            first.shutdown();
        }
    }

    @Test
    void realDockerStreamsLargeHostManifestWithoutGrowingArgv() throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        WorkspaceSpec spec = spec(host);
        Sandbox sandbox = new DockerSandboxClient().create(spec, null, options());
        try {
            sandbox.start();
            assertTrue(
                    sandbox.exec(
                                    null,
                                    "mkdir -p /workspace/.skills-cache/alice/market/a"
                                        + " /workspace/.skills-cache/alice/stale"
                                        + " /workspace/.skills-cache/bob /workspace/outputs; printf"
                                        + " stale > /workspace/.skills-cache/alice/stale/file.txt;"
                                        + " printf bob > /workspace/.skills-cache/bob/private.txt;"
                                        + " printf output > /workspace/outputs/user.txt",
                                    10)
                            .ok());
            for (int index :
                    List.of(
                            0,
                            SkillsCacheProjectionTransportTest.FILE_COUNT / 2,
                            SkillsCacheProjectionTransportTest.FILE_COUNT - 1)) {
                assertTrue(
                        sandbox.exec(
                                        null,
                                        "printf retain > /workspace/"
                                                + SkillsCacheProjectionTransportTest.path(index),
                                        10)
                                .ok());
            }
            SkillsCacheProjection.reconcile(
                    (AbstractBaseSandbox) sandbox,
                    spec,
                    SkillsCacheProjectionTransportTest.largePayload(),
                    "/workspace");
            for (int index :
                    List.of(
                            0,
                            SkillsCacheProjectionTransportTest.FILE_COUNT / 2,
                            SkillsCacheProjectionTransportTest.FILE_COUNT - 1)) {
                assertEquals(
                        "retain",
                        sandbox.exec(
                                        null,
                                        "cat /workspace/"
                                                + SkillsCacheProjectionTransportTest.path(index),
                                        10)
                                .stdout());
            }
            assertTrue(
                    sandbox.exec(null, "test ! -e /workspace/.skills-cache/alice/stale", 10).ok());
            assertEquals(
                    "bob",
                    sandbox.exec(null, "cat /workspace/.skills-cache/bob/private.txt", 10)
                            .stdout());
            assertEquals(
                    "output", sandbox.exec(null, "cat /workspace/outputs/user.txt", 10).stdout());
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void preservedContainerCannotPretendToBeAnExclusiveNewMaintenanceEnvironment()
            throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        write(host.resolve(".skills-cache/alice/market/a/file.txt"), "a");
        Sandbox sandbox = new DockerSandboxClient().create(spec(host), null, options());
        try {
            sandbox.start();
            String hash = sandbox.getState().getWorkspaceProjectionHash();
            assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);
            assertEquals(hash, sandbox.getState().getWorkspaceProjectionHash());
            assertTrue(
                    sandbox.exec(
                                    null,
                                    "test -f /workspace/.skills-cache/alice/market/a/file.txt",
                                    10)
                            .ok());
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void undeclaredDockerMountIsRejectedBeforeSnapshotOrProjectionWrites() throws Exception {
        Path host = Files.createDirectories(temp.resolve("host"));
        Path external = temp.resolve("external");
        write(external.resolve("user.txt"), "private data");
        write(host.resolve(".skills-cache/alice/market/a/file.txt"), "a");
        DockerSandboxClientOptions options = options();
        options.additionalRunArgs(
                "--mount",
                "type=bind,source="
                        + external.toAbsolutePath()
                        + ",target=/workspace/.skills-cache/alice/unknown");
        Sandbox sandbox = new DockerSandboxClient().create(spec(host), null, options);
        try {
            assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);
            assertEquals("private data", Files.readString(external.resolve("user.txt")));
            assertEquals(null, sandbox.getState().getWorkspaceProjectionHash());
            assertThrows(
                    SandboxException.ExecException.class,
                    () ->
                            sandbox.exec(
                                    null,
                                    "test -f /workspace/.skills-cache/alice/market/a/file.txt",
                                    10));
        } finally {
            sandbox.shutdown();
        }
    }

    private static WorkspaceSpec spec(Path host) {
        WorkspaceProjectionEntry entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(host.toString());
        entry.setIncludeRoots(List.of(".skills-cache"));
        entry.setSyncRoots(List.of(".skills-cache/alice"));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("projection", entry);
        return spec;
    }

    private static DockerSandboxClientOptions options() {
        return new DockerSandboxClientOptions().image("python:3.12-slim");
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
