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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BaseSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void glob_recursivePattern_stripsDoubleStarPrefixBeforeFindName() {
        FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

        GlobResult result = filesystem.glob(RT, "**/*.md", "/workspace");

        assertTrue(result.isSuccess());
        assertEquals(
                "find '/workspace' -type f -name '*.md' 2>/dev/null | sort",
                filesystem.lastCommand);
        assertEquals(
                List.of("/workspace/README.md", "/workspace/docs/guide.md"),
                result.matches().stream().map(match -> match.path()).collect(Collectors.toList()));
    }

    @Test
    void glob_plainPattern_keepsFindNamePatternUnchanged() {
        FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

        GlobResult result = filesystem.glob(RT, "*.md", "/workspace");

        assertTrue(result.isSuccess());
        assertEquals(
                "find '/workspace' -type f -name '*.md' 2>/dev/null | sort",
                filesystem.lastCommand);
        assertEquals(
                List.of("/workspace/README.md", "/workspace/docs/guide.md"),
                result.matches().stream().map(match -> match.path()).collect(Collectors.toList()));
    }

    private static final class FakeSandboxFilesystem extends BaseSandboxFilesystem {

        private String lastCommand;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            lastCommand = command;
            if ("find '/workspace' -type f -name '*.md' 2>/dev/null | sort".equals(command)) {
                return new ExecuteResponse(
                        "/workspace/README.md\n/workspace/docs/guide.md\n", 0, false);
            }
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }
}
