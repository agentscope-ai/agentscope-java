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
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BaseSandboxFilesystemAmbiguousEditTest {

    @TempDir Path directory;

    static Stream<Arguments> ambiguousOutcomes() {
        return Stream.of(
                Arguments.of("timeout", new ExecuteResponse("edit timed out", 124, false), true),
                Arguments.of("transport", new ExecuteResponse("HTTP 504", -1, false), false),
                Arguments.of("truncated", new ExecuteResponse("", 0, true), true),
                Arguments.of("missing marker", new ExecuteResponse(null, 0, false), false),
                Arguments.of("exception", null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguousOutcomes")
    void doesNotReplayAnEditWhoseNativeResultWasLost(
            String outcome, ExecuteResponse response, boolean replaceAll) throws IOException {
        Path file = directory.resolve("settings.txt");
        Files.writeString(file, "limit = 1;");
        String oldString = replaceAll ? "1" : "limit";
        String newString = replaceAll ? "11" : "limit_extra";
        String committedContent = replaceAll ? "limit = 11;" : "limit_extra = 1;";
        CommittedEditFilesystem filesystem =
                new CommittedEditFilesystem(file, committedContent, response);

        EditResult result =
                filesystem.edit(
                        RuntimeContext.empty(), file.toString(), oldString, newString, replaceAll);

        assertEquals(committedContent, Files.readString(file), "the native edit already committed");
        assertFalse(result.isSuccess(), "an unconfirmed result must not be reported as success");
        assertTrue(result.error().contains("settings.txt"), result.error());
        assertEquals(0, filesystem.downloads, "do not start another edit after an unknown outcome");
        assertEquals(0, filesystem.uploads, "do not upload a second replacement");
    }

    /** Models a remote write that commits before its result is lost at the execute boundary. */
    private static final class CommittedEditFilesystem extends BaseSandboxFilesystem {
        private final Path file;
        private final String committedContent;
        private final ExecuteResponse response;
        private int downloads;
        private int uploads;

        private CommittedEditFilesystem(
                Path file, String committedContent, ExecuteResponse response) {
            this.file = file;
            this.committedContent = committedContent;
            this.response = response;
        }

        @Override
        public String id() {
            return "committed-edit-lost-response";
        }

        @Override
        public ExecuteResponse execute(RuntimeContext context, String command, Integer timeout) {
            assertTrue(command.startsWith("python3 "), command);
            try {
                Files.writeString(file, committedContent);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (response == null) {
                throw new IllegalStateException("connection lost after edit");
            }
            return response;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext context, List<String> paths) {
            downloads++;
            try {
                return List.of(
                        FileDownloadResponse.success(file.toString(), Files.readAllBytes(file)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
            uploads++;
            try {
                Files.write(file, files.get(0).getValue());
                return List.of(FileUploadResponse.success(file.toString()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
