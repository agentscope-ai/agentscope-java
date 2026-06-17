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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentGenerateToolTest {

    private static final String VALID_SPEC =
            """
            ---
            description: Reviews code for security issues.
            mode: subagent
            ---
            You are a code reviewer. Focus on security.
            """;

    private static Model fixedModel(String markdown) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                TextBlock.builder().text(markdown).build()))
                                .build());
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
    }

    /** Captures the single {@code write} call; throws on every other method. */
    private static final class RecordingFs implements AbstractFilesystem {
        String writtenPath;
        String writtenContent;

        @Override
        public WriteResult write(RuntimeContext rc, String filePath, String content) {
            this.writtenPath = filePath;
            this.writtenContent = content;
            return WriteResult.ok(filePath);
        }

        // Everything else is unused in these tests.
        @Override
        public LsResult ls(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EditResult edit(
                RuntimeContext rc,
                String filePath,
                String oldString,
                String newString,
                boolean replaceAll) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult delete(RuntimeContext rc, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteResult move(RuntimeContext rc, String fromPath, String toPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return false;
        }
    }

    private static DefaultAgentManager emptyManager() {
        return new DefaultAgentManager(List.of(), null);
    }

    private static DefaultAgentManager managerWith(String name) {
        SubagentFactory noopFactory = (rc) -> (Agent) null;
        SubagentDeclaration existing =
                SubagentDeclaration.builder()
                        .name(name)
                        .description("already there")
                        .inlineAgentsBody("b")
                        .build();
        return new DefaultAgentManager(
                List.of(new SubagentEntry(name, existing.getDescription(), noopFactory, existing)),
                null);
    }

    @Test
    void dryRun_doesNotWrite() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(VALID_SPEC));
        RecordingFs fs = new RecordingFs();
        AgentGenerateTool tool = new AgentGenerateTool(gen, emptyManager(), fs);

        String result =
                tool.agentGenerate(RuntimeContext.empty(), "code-reviewer", "review code", true)
                        .block();

        assertNotNull(result);
        assertTrue(result.startsWith("dry_run=true"));
        assertTrue(result.contains("code reviewer"));
        assertNull(fs.writtenPath, "filesystem.write must not be called in dry_run mode");
    }

    @Test
    void writesGeneratedSpecToSubagentsDir() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(VALID_SPEC));
        RecordingFs fs = new RecordingFs();
        AgentGenerateTool tool = new AgentGenerateTool(gen, emptyManager(), fs);

        String result =
                tool.agentGenerate(RuntimeContext.empty(), "code-reviewer", "review code", false)
                        .block();

        assertNotNull(result);
        assertTrue(result.startsWith("Wrote subagent spec to "));
        assertEquals("subagents/code-reviewer.md", fs.writtenPath);
        assertTrue(fs.writtenContent.contains("description: Reviews code"));
    }

    @Test
    void nameCollision_rejected() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(VALID_SPEC));
        AgentGenerateTool tool =
                new AgentGenerateTool(gen, managerWith("code-reviewer"), new RecordingFs());

        String result =
                tool.agentGenerate(RuntimeContext.empty(), "code-reviewer", "review code", false)
                        .block();

        assertEquals("Error: agent 'code-reviewer' already exists", result);
    }

    @Test
    void invalidNamePattern_rejected() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(VALID_SPEC));
        AgentGenerateTool tool = new AgentGenerateTool(gen, emptyManager(), new RecordingFs());

        String result =
                tool.agentGenerate(
                                RuntimeContext.empty(),
                                "Code_Reviewer", // underscore + capitals
                                "review code",
                                false)
                        .block();
        assertNotNull(result);
        assertTrue(result.startsWith("Error: name "));
    }

    @Test
    void missingFilesystem_returnsError() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(VALID_SPEC));
        AgentGenerateTool tool = new AgentGenerateTool(gen, emptyManager(), null);

        String result =
                tool.agentGenerate(RuntimeContext.empty(), "code-reviewer", "review code", false)
                        .block();
        assertNotNull(result);
        assertTrue(result.startsWith("Error: no filesystem configured"));
    }

    @Test
    void malformedLlmOutput_surfacedAsError() {
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel("not a markdown spec"));
        RecordingFs fs = new RecordingFs();
        AgentGenerateTool tool = new AgentGenerateTool(gen, emptyManager(), fs);

        String result =
                tool.agentGenerate(RuntimeContext.empty(), "weird", "weird agent", false).block();
        assertNotNull(result);
        assertTrue(result.startsWith("Error: "));
        assertNull(fs.writtenPath, "no write on malformed spec");
    }
}
