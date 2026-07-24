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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalFilesystemWithShellTest {

    private static final int LARGE_OUTPUT_BYTES = 200_000;

    @Test
    @Timeout(20)
    void executeDrainsLargeStdoutWhileWaiting(@TempDir Path tmp) {
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tmp, false, 10, 300_000, null, false);

        ExecuteResponse response =
                fs.execute(RuntimeContext.empty(), "yes x | head -c " + LARGE_OUTPUT_BYTES, 10);

        assertEquals(0, response.exitCode());
        assertFalse(response.truncated());
        assertTrue(response.output().length() >= LARGE_OUTPUT_BYTES);
    }

    @Test
    @Timeout(20)
    void executeDrainsLargeStderrWhileWaiting(@TempDir Path tmp) {
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tmp, false, 10, 300_000, null, false);

        ExecuteResponse response =
                fs.execute(
                        RuntimeContext.empty(),
                        "yes e | tr -d '\\n' | head -c " + LARGE_OUTPUT_BYTES + " >&2",
                        10);

        assertEquals(0, response.exitCode());
        assertFalse(response.truncated());
        assertTrue(response.output().startsWith("[stderr] "));
        assertTrue(response.output().length() >= LARGE_OUTPUT_BYTES);
    }
}
