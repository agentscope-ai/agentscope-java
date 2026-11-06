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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class LazyFileSystemSkillLoadingTest {

    @TempDir Path temp;

    @Test
    void harnessLoadsSkillThenLazyResourceAcrossModelRounds() throws IOException {
        FileSystemSkillRepository repository = repository();
        AtomicInteger rounds = new AtomicInteger();
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "lazy-skill-test";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int round = rounds.getAndIncrement();
                        if (round == 1) {
                            assertTrue(toolText(messages).contains("Read references/guide.md"));
                        }
                        if (round < 2) {
                            String path = round == 0 ? "SKILL.md" : "references/guide.md";
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("load-" + round)
                                                                    .name(SkillLoadTool.TOOL_NAME)
                                                                    .content(
                                                                            "{\"skillId\":\"alpha_audit\",\"path\":\""
                                                                                    + path
                                                                                    + "\"}")
                                                                    .input(
                                                                            Map.of(
                                                                                    "skillId",
                                                                                    "alpha_audit",
                                                                                    "path",
                                                                                    path))
                                                                    .build()))
                                            .build());
                        }
                        assertTrue(
                                toolText(messages).contains("GUIDE_FROM_DISK"),
                                "the second loader result must reach the model");
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("lazy-skill")
                        .model(model)
                        .disableMemoryHooks()
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .sessionLogStore(new InMemorySessionLogStore())
                        .skillRepository(repository)
                        .build()) {
            assertNotNull(
                    agent.call(
                                    new UserMessage("load alpha and its guide"),
                                    RuntimeContext.builder().sessionId("audit").build())
                            .block());
        }
        assertEquals(3, rounds.get());
    }

    @Test
    void missingResourcesListDiskFilesAndRejectTraversal() throws IOException {
        var middleware = new HarnessSkillMiddleware(List.of(repository()), new Toolkit());
        RuntimeContext ctx = RuntimeContext.empty();
        middleware.onSystemPrompt(null, ctx, "").block();
        String missing = load(ctx, "missing.md");
        assertTrue(missing.contains("references/guide.md"));
        for (String path :
                List.of(
                        "../outside.txt",
                        "/outside.txt",
                        temp.resolve("library/outside.txt").toString(),
                        ".",
                        "./")) {
            assertFalse(load(ctx, path).contains("OUTSIDE_SENTINEL"), path);
        }
        assertNull(middleware.runtime().currentCatalog(ctx).get("alpha_audit").filesRoot());
    }

    @Test
    void binaryAccessorPreservesLiteralBase64Prefix() throws IOException {
        FileSystemSkillRepository repo = repository();
        Path root = repo.getSkill("alpha").getOriginDir().orElseThrow();
        byte[] content = "base64:aGVsbG8=".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("literal.txt"), content);
        var middleware = new HarnessSkillMiddleware(List.of(repo), new Toolkit());
        RuntimeContext ctx = RuntimeContext.empty();
        middleware.onSystemPrompt(null, ctx, "").block();
        assertArrayEquals(
                content,
                middleware
                        .runtime()
                        .currentCatalog(ctx)
                        .get("alpha_audit")
                        .lazyResources()
                        .readBinary("literal.txt")
                        .orElseThrow());
    }

    @Test
    void filteredSkillsCannotUseOriginFallback() throws IOException {
        var middleware =
                new HarnessSkillMiddleware(
                        List.of(repository()), new Toolkit(), SkillFilter.none());
        RuntimeContext ctx = RuntimeContext.empty();
        middleware.onSystemPrompt(null, ctx, "").block();
        assertFalse(load(ctx, "references/guide.md").contains("GUIDE_FROM_DISK"));
    }

    @Test
    void lazyRepositoryDenialDoesNotFallBackToOrigin() throws IOException {
        FileSystemSkillRepository base = repository();
        class DenyingRepository extends FileSystemSkillRepository implements LazyResourceCapable {
            DenyingRepository() {
                super(
                        base.getSkill("alpha").getOriginDir().orElseThrow().getParent(),
                        false,
                        "audit",
                        true);
            }

            @Override
            public SkillResources resourcesFor(String name, RuntimeContext ctx) {
                return SkillResources.empty();
            }
        }
        var middleware =
                new HarnessSkillMiddleware(List.of(new DenyingRepository()), new Toolkit());
        RuntimeContext ctx = RuntimeContext.empty();
        middleware.onSystemPrompt(null, ctx, "").block();
        assertFalse(load(ctx, "references/guide.md").contains("GUIDE_FROM_DISK"));
    }

    private FileSystemSkillRepository repository() throws IOException {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path skill = Files.createDirectories(root.resolve("alpha/references"));
        Files.writeString(
                root.resolve("alpha/SKILL.md"),
                "---\nname: alpha\ndescription: alpha skill\n---\nRead references/guide.md\n");
        Files.writeString(skill.resolve("guide.md"), "GUIDE_FROM_DISK\n");
        Files.writeString(root.resolve("outside.txt"), "OUTSIDE_SENTINEL");
        return new FileSystemSkillRepository(root, false, "audit", true);
    }

    private static String toolText(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static String load(RuntimeContext ctx, String path) {
        Map<String, Object> input = Map.of("skillId", "alpha_audit", "path", path);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("resource")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        var result =
                new SkillLoadTool()
                        .callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(use)
                                        .input(input)
                                        .runtimeContext(ctx)
                                        .build())
                        .block();
        assertNotNull(result);
        return result.getOutput().toString();
    }
}
