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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BaseSandboxFilesystemTest {

    @Test
    void ls_parsesFileSizesFromSandboxOutput() {
        TestSandboxFilesystem filesystem = new TestSandboxFilesystem();
        filesystem.nextExecuteResponse =
                new ExecuteResponse(
                        "DIR\t/workspace/session/uploads/subdir\n"
                                + "FILE\t/workspace/session/uploads/a.pdf\t1234\n",
                        0,
                        false);

        var result = filesystem.ls(RuntimeContext.empty(), "/workspace/session/uploads");

        assertFalse(result.entries().isEmpty());
        assertEquals(2, result.entries().size());
        assertEquals("/workspace/session/uploads/subdir", result.entries().get(0).path());
        assertEquals("/workspace/session/uploads/a.pdf", result.entries().get(1).path());
        assertEquals(1234L, result.entries().get(1).size());
    }

    @Test
    void glob_parsesFileSizesFromSandboxOutput() {
        TestSandboxFilesystem filesystem = new TestSandboxFilesystem();
        filesystem.nextExecuteResponse =
                new ExecuteResponse(
                        "128\t/workspace/session/uploads/a.pdf\n"
                                + "2048\t/workspace/session/uploads/b.xlsx\n",
                        0,
                        false);

        var result = filesystem.glob(RuntimeContext.empty(), "*.pdf", "/workspace/session/uploads");

        assertEquals(2, result.matches().size());
        assertEquals("/workspace/session/uploads/a.pdf", result.matches().get(0).path());
        assertEquals(128L, result.matches().get(0).size());
        assertEquals("/workspace/session/uploads/b.xlsx", result.matches().get(1).path());
        assertEquals(2048L, result.matches().get(1).size());
    }

    private static final class TestSandboxFilesystem extends BaseSandboxFilesystem {
        private ExecuteResponse nextExecuteResponse = new ExecuteResponse("", 0, false);

        @Override
        public String id() {
            return "test";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return nextExecuteResponse;
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            throw new UnsupportedOperationException("Not needed for this test");
        }
    }
}
