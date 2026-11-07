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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.FilesystemDecorator;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** A wrapper passed via {@code abstractFilesystem(...)} must not silently lose shell support. */
@HarnessQuiescence
class HarnessAgentFilesystemDecoratorTest {

    @TempDir Path workspace;

    private final List<HarnessAgent> agents = new ArrayList<>();

    @AfterEach
    void closeAgents() {
        agents.forEach(HarnessAgent::close);
    }

    @Test
    void decoratorAroundShellFilesystem_registersShellTool() {
        HarnessAgent agent = build(decorator(new LocalFilesystemWithShell(workspace)));

        assertTrue(toolNames(agent).contains("execute"));
    }

    @Test
    void wrapperWithoutDecoratorInterface_keepsPreviousBehaviour() {
        AbstractFilesystem inner = new LocalFilesystemWithShell(workspace);
        AbstractFilesystem wrapper =
                (AbstractFilesystem)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {AbstractFilesystem.class},
                                (proxy, method, args) -> method.invoke(inner, args));

        assertFalse(toolNames(build(wrapper)).contains("execute"));
    }

    @Test
    void decoratorAroundNamespacedOverlay_stripsNamespaceFromAbsolutePaths() {
        NamespaceFactory nsFactory = IsolationScope.USER.toNamespaceFactory();
        HarnessAgent agent =
                build(decorator(new LocalFilesystemSpec().toFilesystem(workspace, nsFactory)));
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        Path nsDir = workspace.resolve(String.join("/", nsFactory.getNamespace(ctx)));

        agent.getDelegate()
                .getToolkit()
                .getTool("write_file")
                .callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(ctx)
                                .input(
                                        Map.of(
                                                "path",
                                                nsDir.resolve("note.txt")
                                                        .toAbsolutePath()
                                                        .toString(),
                                                "content",
                                                "hi"))
                                .build())
                .block();

        assertTrue(Files.exists(nsDir.resolve("note.txt")));
        assertFalse(Files.exists(nsDir.resolve(workspace.relativize(nsDir)).resolve("note.txt")));
    }

    @Test
    void decoratorAroundShellFilesystem_keepsLocalSkillShellPolicy() throws Exception {
        Path skillDir = workspace.resolve("skills/demo");
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: Demo skill\n---\n# Body\n");

        HarnessAgent agent = build(decorator(new LocalFilesystemWithShell(workspace)));

        assertEquals(ShellPathPolicy.Mode.LOCAL_WITH_SHELL, skillShellPolicy(agent).mode());
    }

    @Test
    void cyclicDecoratorChain_failsFast() {
        FilesystemDecorator[] self = new FilesystemDecorator[1];
        self[0] = (FilesystemDecorator) decorator(null, () -> self[0]);

        assertThrows(IllegalStateException.class, () -> FilesystemDecorator.innermost(self[0]));
        assertThrows(IllegalStateException.class, () -> FilesystemDecorator.findShell(self[0]));
    }

    @Test
    void findShell_prefersOutermostShellFilesystem() {
        LocalFilesystemWithShell shell = new LocalFilesystemWithShell(workspace);
        AbstractFilesystem plain = new LocalFilesystem(workspace);

        assertSame(shell, FilesystemDecorator.findShell(decorator(decorator(shell))));
        assertSame(null, FilesystemDecorator.findShell(decorator(plain)));
        assertSame(plain, FilesystemDecorator.innermost(decorator(decorator(plain))));
    }

    private static AbstractFilesystem decorator(AbstractFilesystem inner) {
        return decorator(inner, () -> inner);
    }

    private static AbstractFilesystem decorator(
            AbstractFilesystem inner, java.util.function.Supplier<AbstractFilesystem> unwrap) {
        return (AbstractFilesystem)
                Proxy.newProxyInstance(
                        HarnessAgentFilesystemDecoratorTest.class.getClassLoader(),
                        new Class<?>[] {FilesystemDecorator.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("unwrap")) {
                                return unwrap.get();
                            }
                            try {
                                return method.invoke(inner, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    private HarnessAgent build(AbstractFilesystem fs) {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(fs)
                        .build();
        agents.add(agent);
        return agent;
    }

    private static List<String> toolNames(HarnessAgent agent) {
        return agent.getDelegate().getToolkit().getToolSchemas().stream()
                .map(ToolSchema::getName)
                .toList();
    }

    private static ShellPathPolicy skillShellPolicy(HarnessAgent agent) throws Exception {
        HarnessSkillMiddleware middleware =
                agent.getDelegate().getMiddlewares().stream()
                        .filter(HarnessSkillMiddleware.class::isInstance)
                        .map(HarnessSkillMiddleware.class::cast)
                        .findFirst()
                        .orElseThrow();
        Field field = HarnessSkillMiddleware.class.getDeclaredField("shellPathPolicy");
        field.setAccessible(true);
        return (ShellPathPolicy) field.get(middleware);
    }

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
