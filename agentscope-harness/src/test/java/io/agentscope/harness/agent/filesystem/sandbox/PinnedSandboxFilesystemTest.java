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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.SandboxBackgroundWrites;
import io.agentscope.harness.agent.sandbox.TrackingSandbox;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PinnedSandboxFilesystem} around sandbox release (issue #3415). */
class PinnedSandboxFilesystemTest {

    private final List<String> events = TrackingSandbox.newEventLog();
    private final TrackingSandbox sandbox = new TrackingSandbox(events);

    @AfterEach
    void clearBudget() {
        System.clearProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY);
    }

    @Test
    void uploadsWhileTheHoldKeepsTheSandboxAlive() {
        sandbox.start();
        try (SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox)) {
            List<FileUploadResponse> written =
                    new PinnedSandboxFilesystem(sandbox, hold)
                            .uploadFiles(RuntimeContext.empty(), file("a.jsonl"));

            assertTrue(written.get(0).isSuccess(), String.valueOf(written.get(0).error()));
        }
        assertEquals(List.of("upload"), events);
    }

    @Test
    void skipsUploadsOnceTheDeferralBudgetReleasedTheSandbox() throws Exception {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "100");
        sandbox.start();
        SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox);
        try {
            SandboxBackgroundWrites.releaseWhenIdle(sandbox, sandbox::close)
                    .get(5, TimeUnit.SECONDS);

            List<FileUploadResponse> written =
                    new PinnedSandboxFilesystem(sandbox, hold)
                            .uploadFiles(RuntimeContext.empty(), file("a.jsonl"));

            assertFalse(written.get(0).isSuccess());
            assertEquals("a.jsonl", written.get(0).path());
            assertEquals(
                    List.of("stop", "shutdown"),
                    events,
                    "the stopped container must not be exec'd into");
        } finally {
            hold.close();
        }
    }

    private static List<Map.Entry<String, byte[]>> file(String path) {
        return List.of(Map.entry(path, "x".getBytes(StandardCharsets.UTF_8)));
    }
}
