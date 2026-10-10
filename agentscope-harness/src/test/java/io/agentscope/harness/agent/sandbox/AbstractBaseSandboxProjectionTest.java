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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.testing.ProjectionTestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbstractBaseSandboxProjectionTest {
    @TempDir Path temp;

    @Test
    void unchangedHostHashDoesNotHideSandboxTampering() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        String hash = sandbox.getState().getWorkspaceProjectionHash();
        sandbox.stop();
        Files.writeString(sandbox.directory().resolve("AGENTS.md"), "INJECTED");
        sandbox.start();
        assertEquals(hash, sandbox.getState().getWorkspaceProjectionHash());
        assertEquals("HOST", Files.readString(sandbox.directory().resolve("AGENTS.md")));
        assertEquals(2, sandbox.hydrations);
    }

    @Test
    void missingProjectedFileIsRestored() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        Files.delete(sandbox.directory().resolve("AGENTS.md"));
        sandbox.start();
        assertTrue(Files.isRegularFile(sandbox.directory().resolve("AGENTS.md")));
        assertEquals("HOST", Files.readString(sandbox.directory().resolve("AGENTS.md")));
    }

    @Test
    void legacyOptOutSkipsTamperVerification() throws Exception {
        var sandbox = sandbox();
        ((WorkspaceProjectionEntry)
                        sandbox.getState().getWorkspaceSpec().getEntries().get("projection"))
                .setHostAuthoritativeDefinitions(false);
        sandbox.start();
        sandbox.stop();
        Files.writeString(sandbox.directory().resolve("AGENTS.md"), "INJECTED");
        sandbox.start();
        assertEquals("INJECTED", Files.readString(sandbox.directory().resolve("AGENTS.md")));
        assertEquals(1, sandbox.hydrations);
    }

    @Test
    void changedHostPayloadRehydrates() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        Files.writeString(temp.resolve("host/AGENTS.md"), "UPDATED");
        sandbox.start();
        assertEquals("UPDATED", Files.readString(sandbox.directory().resolve("AGENTS.md")));
        assertEquals(2, sandbox.hydrations);
    }

    @Test
    void verificationFailureRehydrates() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        sandbox.failVerification = true;
        sandbox.start();
        assertEquals(2, sandbox.hydrations);
    }

    @Test
    void intactProjectionSkipsHydration() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        sandbox.start();
        assertEquals(1, sandbox.hydrations);
    }

    @Test
    void unsupportedVerificationIsCachedButStillRehydratesOnEveryStart() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        sandbox.unsupportedVerification = true;
        sandbox.start();
        sandbox.stop();
        Files.writeString(sandbox.directory().resolve("AGENTS.md"), "INJECTED");
        sandbox.start();
        assertEquals(1, sandbox.verificationCalls);
        assertEquals(3, sandbox.hydrations);
        assertEquals("HOST", Files.readString(sandbox.directory().resolve("AGENTS.md")));
    }

    @Test
    void interruptedVerificationRestoresInterruptFlagAndRehydrates() throws Exception {
        var sandbox = sandbox();
        sandbox.start();
        sandbox.stop();
        sandbox.interruptVerification = true;
        try {
            sandbox.start();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, sandbox.hydrations);
        } finally {
            Thread.interrupted();
        }
    }

    private ProjectionTestClient.LocalSandbox sandbox() throws Exception {
        Path host = temp.resolve("host");
        Files.createDirectories(host);
        Files.writeString(host.resolve("AGENTS.md"), "HOST");
        WorkspaceProjectionEntry entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(host.toString());
        entry.setIncludeRoots(List.of("AGENTS.md"));
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.getEntries().put("projection", entry);
        ProjectionTestClient client = new ProjectionTestClient(temp.resolve("sandbox"));
        return (ProjectionTestClient.LocalSandbox) client.create(spec, null, null);
    }
}
