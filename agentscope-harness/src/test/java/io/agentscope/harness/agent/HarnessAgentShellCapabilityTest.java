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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
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
    private static class DelegatingFilesystem implements AbstractFilesystem {
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

    /**
     * Wrapper holding two filesystems. It behaves like {@link DelegatingFilesystem} but has a
     * second filesystem to choose from, so it is not a pass-through wrapper.
     */
    private static final class TwoDelegates extends DelegatingFilesystem {
        @SuppressWarnings("unused")
        private final AbstractFilesystem other;

        TwoDelegates(AbstractFilesystem first, AbstractFilesystem second) {
            super(first);
            this.other = second;
        }
    }

    /** Smallest sandbox-capable filesystem: no host shell, every operation is a no-op. */
    private static final class FakeSandbox extends BaseSandboxFilesystem {
        @Override
        public String id() {
            return "fake-sandbox";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
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

    // ==================== resolution shared with main's routing-layer helpers ====================
    // main resolves the backend behind composites and routed sandboxes with
    // FilesystemUtils.primaryBackend; pass-through wrappers are resolved on top of that, so
    // every configuration without such a wrapper behaves exactly as before.

    @Test
    void skillShellPolicy_followsAPassThroughWrapper() {
        Path root = workspace;
        assertEquals(
                ShellPathPolicy.Mode.LOCAL_WITH_SHELL,
                HarnessAgent.skillShellPolicy(
                                new DelegatingFilesystem(new LocalFilesystemWithShell(root)),
                                null,
                                root)
                        .mode(),
                "a wrapper over a host-shell filesystem advertises the host shell");
        assertEquals(
                ShellPathPolicy.Mode.SANDBOX,
                HarnessAgent.skillShellPolicy(
                                new DelegatingFilesystem(new FakeSandbox()), null, root)
                        .mode(),
                "a wrapper over a sandbox advertises the sandbox");
        assertEquals(
                ShellPathPolicy.Mode.NO_SHELL,
                HarnessAgent.skillShellPolicy(
                                new DelegatingFilesystem(new LocalFilesystem(root)), null, root)
                        .mode(),
                "a wrapper over a filesystem without a shell advertises none");
    }

    @Test
    void skillShellPolicy_treatsAWrapperAsTransparent() {
        Path root = workspace;
        List<AbstractFilesystem> delegates =
                List.of(
                        new LocalFilesystemWithShell(root),
                        new LocalFilesystem(root),
                        new FakeSandbox(),
                        new OverlayFilesystem(
                                new LocalFilesystemWithShell(root), new LocalFilesystem(root)));
        for (AbstractFilesystem delegate : delegates) {
            assertEquals(
                    HarnessAgent.skillShellPolicy(delegate, null, root).mode(),
                    HarnessAgent.skillShellPolicy(new DelegatingFilesystem(delegate), null, root)
                            .mode(),
                    () ->
                            "wrapping must not change the policy for "
                                    + delegate.getClass().getSimpleName());
        }
    }

    @Test
    void passThroughDelegate_seesThroughUnambiguousWrappersOnly() {
        AbstractFilesystem plain = new LocalFilesystem(workspace);
        AbstractFilesystem wrapper = new DelegatingFilesystem(plain);

        assertSame(plain, HarnessAgent.passThroughDelegate(wrapper));
        assertSame(
                plain,
                HarnessAgent.passThroughDelegate(new DelegatingFilesystem(wrapper)),
                "a chain of wrappers is followed to its end");

        assertNull(HarnessAgent.passThroughDelegate(plain), "nothing to delegate to");
        assertNull(
                HarnessAgent.passThroughDelegate(
                        new TwoDelegates(plain, new LocalFilesystem(workspace))),
                "a wrapper that has several filesystems to choose from");
        assertNull(
                HarnessAgent.passThroughDelegate(new CompositeFilesystem(plain, Map.of())),
                "the framework's routing layers are not pass-through wrappers");
        assertNull(
                HarnessAgent.passThroughDelegate(
                        new OverlayFilesystem(new LocalFilesystemWithShell(workspace), plain)));
        assertNull(
                HarnessAgent.passThroughDelegate(
                        new BakedContextFilesystem(plain, RuntimeContext.empty())),
                "a context-bound view adds behaviour of its own");
    }

    @Test
    void passThroughDelegate_returnsTheRoutingLayerAWrapperEndsIn() {
        FakeSandbox sandbox = new FakeSandbox();
        CompositeFilesystem composite = new CompositeFilesystem(sandbox, Map.of());

        assertSame(
                composite,
                HarnessAgent.passThroughDelegate(new DelegatingFilesystem(composite)),
                "the wrapper is seen through; the routing layer behind it is not");
    }

    @Test
    void servingBackend_findsTheBackendBehindWrappersAndRoutingLayersInAnyOrder() {
        FakeSandbox sandbox = new FakeSandbox();

        assertSame(sandbox, HarnessAgent.servingBackend(new DelegatingFilesystem(sandbox)));
        assertSame(
                sandbox,
                HarnessAgent.servingBackend(
                        new CompositeFilesystem(new DelegatingFilesystem(sandbox), Map.of())),
                "a wrapper used as a composite's default backend");
        assertSame(
                sandbox,
                HarnessAgent.servingBackend(
                        new DelegatingFilesystem(new CompositeFilesystem(sandbox, Map.of()))),
                "a wrapper around a composite");
        assertSame(
                sandbox,
                HarnessAgent.servingBackend(new CompositeFilesystem(sandbox, Map.of())),
                "without a wrapper this is FilesystemUtils.primaryBackend");
        assertNull(HarnessAgent.servingBackend(null));
    }

    @Test
    void wrapperOverSandbox_keepsShellExecuteToolRegistered() {
        FakeSandbox sandbox = new FakeSandbox();
        HarnessAgent agent =
                track(
                        HarnessAgent.builder()
                                .name("t")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .abstractFilesystem(new DelegatingFilesystem(sandbox))
                                .build());

        assertNotNull(
                agent.getDelegate().getToolkit().getTool(ShellExecuteTool.NAME),
                "a pass-through wrapper around a sandbox must keep ShellExecuteTool registered");
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
