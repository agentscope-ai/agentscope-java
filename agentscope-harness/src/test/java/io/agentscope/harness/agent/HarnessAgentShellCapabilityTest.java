/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Shell capability detection through filesystem wrappers (#3671): a pure pass-through wrapper
 * over {@link LocalFilesystemWithShell} must keep {@code ShellExecuteTool} registered, while a
 * wrapper over a plain {@link LocalFilesystem} must not gain a tool it cannot serve.
 */
class HarnessAgentShellCapabilityTest {

    @TempDir Path workspace;

    private final List<HarnessAgent> agents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(workspace);
    }

    @AfterEach
    void closeAgents() {
        agents.forEach(HarnessAgent::close);
    }

    /** Pure pass-through wrapper: every method forwards to the single delegate. */
    private static final class DelegatingFilesystem implements AbstractFilesystem {
        private final AbstractFilesystem delegate;

        DelegatingFilesystem(AbstractFilesystem delegate) {
            this.delegate = delegate;
        }

        @Override
        public LsResult ls(RuntimeContext rc, String path) {
            return delegate.ls(rc, path);
        }

        @Override
        public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
            return delegate.read(rc, filePath, offset, limit);
        }

        @Override
        public WriteResult write(RuntimeContext rc, String filePath, String content) {
            return delegate.write(rc, filePath, content);
        }

        @Override
        public EditResult edit(
                RuntimeContext rc,
                String filePath,
                String oldString,
                String newString,
                boolean replaceAll) {
            return delegate.edit(rc, filePath, oldString, newString, replaceAll);
        }

        @Override
        public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
            return delegate.grep(rc, pattern, path, glob);
        }

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            return delegate.glob(rc, pattern, path);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
            return delegate.uploadFiles(rc, files);
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            return delegate.downloadFiles(rc, paths);
        }

        @Override
        public WriteResult delete(RuntimeContext rc, String path) {
            return delegate.delete(rc, path);
        }

        @Override
        public WriteResult move(RuntimeContext rc, String fromPath, String toPath) {
            return delegate.move(rc, fromPath, toPath);
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return delegate.exists(rc, path);
        }
    }

    @Test
    void wrapperOverShellLocal_keepsShellExecuteToolRegistered() {
        HarnessAgent agent =
                track(
                        HarnessAgent.builder()
                                .name("t")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .abstractFilesystem(
                                        new DelegatingFilesystem(
                                                new LocalFilesystemWithShell(workspace)))
                                .build());

        assertNotNull(
                agent.getDelegate().getToolkit().getTool(ShellExecuteTool.NAME),
                "a pass-through wrapper over a shell-capable filesystem must keep"
                        + " ShellExecuteTool registered");
    }

    @Test
    void plainShellLocal_stillRegistersShellExecuteTool() {
        HarnessAgent agent =
                track(
                        HarnessAgent.builder()
                                .name("t")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                                .build());

        assertNotNull(
                agent.getDelegate().getToolkit().getTool(ShellExecuteTool.NAME),
                "plain shell-capable filesystem registration must not regress");
    }

    @Test
    void wrapperOverPlainLocal_doesNotRegisterShellExecuteTool() {
        HarnessAgent agent =
                track(
                        HarnessAgent.builder()
                                .name("t")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .abstractFilesystem(
                                        new DelegatingFilesystem(new LocalFilesystem(workspace)))
                                .build());

        assertNull(
                agent.getDelegate().getToolkit().getTool(ShellExecuteTool.NAME),
                "a wrapper over a filesystem without shell capability must not gain the tool");
    }

    private HarnessAgent track(HarnessAgent agent) {
        agents.add(agent);
        return agent;
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
