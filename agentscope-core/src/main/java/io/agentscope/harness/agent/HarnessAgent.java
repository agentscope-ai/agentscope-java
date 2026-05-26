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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.Session;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.hook.CompactionHook;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @deprecated Use {@link ReActAgent} directly via {@link ReActAgent#builder()}. All harness
 *     capabilities (workspace, sandbox, subagents, skills, compaction, MCP / tools.json, etc.) are
 *     now built into {@link ReActAgent.Builder}. This class is a backwards-compatible thin shell
 *     that delegates every call to an inner {@link ReActAgent} and will be removed in a future
 *     release.
 */
@Deprecated(since = "v2", forRemoval = true)
public class HarnessAgent implements Agent, StateModule, AutoCloseable {

    private final ReActAgent inner;

    private HarnessAgent(ReActAgent inner) {
        this.inner = inner;
    }

    @Override
    public void close() {
        inner.close();
    }

    public Mono<Msg> call(Msg msg, RuntimeContext ctx) {
        return inner.call(List.of(msg), ctx);
    }

    public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
        return inner.call(msgs, ctx);
    }

    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        return inner.stream(msgs, options, ctx);
    }

    public Flux<Event> stream(List<Msg> msgs, RuntimeContext ctx) {
        return inner.stream(msgs, StreamOptions.defaults(), ctx);
    }

    public Flux<Event> stream(Msg msg, RuntimeContext ctx) {
        return inner.stream(List.of(msg), StreamOptions.defaults(), ctx);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return inner.call(msgs);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return inner.call(msgs, structuredModel);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return inner.call(msgs, schema);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return inner.stream(msgs, options);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return inner.stream(msgs, options, structuredModel);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return inner.stream(msgs, options, schema);
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return inner.observe(msg);
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return inner.observe(msgs);
    }

    @Override
    public void interrupt() {
        inner.interrupt();
    }

    @Override
    public void interrupt(Msg msg) {
        inner.interrupt(msg);
    }

    @Override
    public String getName() {
        return inner.getName();
    }

    @Override
    public String getAgentId() {
        return inner.getAgentId();
    }

    @Override
    public String getDescription() {
        return inner.getDescription();
    }

    public ReActAgent getDelegate() {
        return inner;
    }

    public Memory getMemory() {
        return inner.getMemory();
    }

    public Model getModel() {
        return inner.getModel();
    }

    public Toolkit getToolkit() {
        return inner.getToolkit();
    }

    public int getMaxIters() {
        return inner.getMaxIters();
    }

    public WorkspaceManager getWorkspaceManager() {
        return inner.getWorkspaceManager();
    }

    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        return inner.workspaceFor(userId, sessionId);
    }

    public CompactionHook getCompactionHook() {
        return inner.getCompactionHook();
    }

    public RuntimeContext getRuntimeContext() {
        return inner.getRuntimeContext();
    }

    public List<AgentSkillRepository> getSkillRepositories() {
        return inner.getSkillRepositories();
    }

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        inner.saveTo(session, sessionKey);
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        inner.loadFrom(session, sessionKey);
    }

    @Override
    public boolean loadIfExists(Session session, SessionKey sessionKey) {
        return inner.loadIfExists(session, sessionKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated Use {@link ReActAgent.Builder#fromAgent(ReActAgent)} directly.
     */
    @Deprecated(since = "v2", forRemoval = true)
    public static Builder from(ReActAgent agent) {
        return new Builder(ReActAgent.Builder.fromAgent(agent));
    }

    /**
     * @deprecated Use {@link ReActAgent.Builder} directly. All setters here forward to the inner
     *     builder.
     */
    @Deprecated(since = "v2", forRemoval = true)
    public static class Builder {

        private final ReActAgent.Builder b;

        Builder() {
            this(ReActAgent.builder());
        }

        Builder(ReActAgent.Builder inner) {
            this.b = inner;
        }

        public Builder name(String name) {
            b.name(name);
            return this;
        }

        public Builder agentId(String agentId) {
            b.agentId(agentId);
            return this;
        }

        public Builder description(String description) {
            b.description(description);
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            b.sysPrompt(sysPrompt);
            return this;
        }

        public Builder model(Model model) {
            b.model(model);
            return this;
        }

        public Builder model(String modelId) {
            b.model(modelId);
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            b.toolkit(toolkit);
            return this;
        }

        public Builder maxIters(int maxIters) {
            b.maxIters(maxIters);
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig config) {
            b.modelExecutionConfig(config);
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig config) {
            b.toolExecutionConfig(config);
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            b.generateOptions(options);
            return this;
        }

        public Builder hook(Hook hook) {
            b.hook(hook);
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            b.hooks(hooks);
            return this;
        }

        public Builder skillRepository(AgentSkillRepository skillRepository) {
            b.skillRepository(skillRepository);
            return this;
        }

        public Builder skillRepositories(List<AgentSkillRepository> repositories) {
            b.skillRepositories(repositories);
            return this;
        }

        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            b.projectGlobalSkillsDir(projectGlobalSkillsDir);
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            b.toolExecutionContext(ctx);
            return this;
        }

        public Builder knowledge(Knowledge knowledge) {
            b.knowledge(knowledge);
            return this;
        }

        public Builder knowledges(List<Knowledge> knowledges) {
            b.knowledges(knowledges);
            return this;
        }

        public Builder ragMode(RAGMode mode) {
            b.ragMode(mode);
            return this;
        }

        public Builder retrieveConfig(RetrieveConfig config) {
            b.retrieveConfig(config);
            return this;
        }

        public Builder planNotebook(PlanNotebook planNotebook) {
            b.planNotebook(planNotebook);
            return this;
        }

        public Builder enablePlan() {
            b.enablePlan();
            return this;
        }

        public Builder longTermMemory(LongTermMemory longTermMemory) {
            b.longTermMemory(longTermMemory);
            return this;
        }

        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            b.longTermMemoryMode(mode);
            return this;
        }

        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            b.longTermMemoryAsyncRecord(asyncRecord);
            return this;
        }

        public Builder statePersistence(StatePersistence statePersistence) {
            b.statePersistence(statePersistence);
            return this;
        }

        public Builder structuredOutputReminder(StructuredOutputReminder reminder) {
            b.structuredOutputReminder(reminder);
            return this;
        }

        public Builder enableMetaTool(boolean enableMetaTool) {
            b.enableMetaTool(enableMetaTool);
            return this;
        }

        public Builder enablePendingToolRecovery(boolean enable) {
            b.enablePendingToolRecovery(enable);
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            b.checkRunning(checkRunning);
            return this;
        }

        public Builder workspace(Path workspace) {
            b.workspace(workspace);
            return this;
        }

        public Builder workspace(String path) {
            b.workspace(path);
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            b.environmentMemory(environmentMemory);
            return this;
        }

        public Builder abstractFilesystem(AbstractFilesystem backend) {
            b.abstractFilesystem(backend);
            return this;
        }

        public Builder filesystem(SandboxFilesystemSpec spec) {
            b.filesystem(spec);
            return this;
        }

        public Builder filesystem(RemoteFilesystemSpec spec) {
            b.filesystem(spec);
            return this;
        }

        public Builder filesystem(LocalFilesystemSpec spec) {
            b.filesystem(spec);
            return this;
        }

        public Builder enableAgentTracingLog(boolean enabled) {
            b.enableAgentTracingLog(enabled);
            return this;
        }

        public Builder disableFilesystemTools() {
            b.disableFilesystemTools();
            return this;
        }

        public Builder disableShellTool() {
            b.disableShellTool();
            return this;
        }

        public Builder disableDynamicSkills() {
            b.disableDynamicSkills();
            return this;
        }

        public Builder disableDynamicSubagents() {
            b.disableDynamicSubagents();
            return this;
        }

        public Builder disableMemoryTools() {
            b.disableMemoryTools();
            return this;
        }

        public Builder disableMemoryHooks() {
            b.disableMemoryHooks();
            return this;
        }

        public Builder disableSessionPersistence() {
            b.disableSessionPersistence();
            return this;
        }

        public Builder disableWorkspaceContext() {
            b.disableWorkspaceContext();
            return this;
        }

        public Builder disableSubagents() {
            b.disableSubagents();
            return this;
        }

        public Builder disableToolsConfig() {
            b.disableToolsConfig();
            return this;
        }

        public Builder toolsConfig(ToolsConfig toolsConfig) {
            b.toolsConfig(toolsConfig);
            return this;
        }

        public Builder compaction(CompactionConfig config) {
            b.compaction(config);
            return this;
        }

        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            b.toolResultEviction(config);
            return this;
        }

        public Builder session(Session session) {
            b.session(session);
            return this;
        }

        public Builder sandboxDistributed(SandboxDistributedOptions options) {
            b.sandboxDistributed(options);
            return this;
        }

        public Builder subagent(SubagentDeclaration declaration) {
            b.subagent(declaration);
            return this;
        }

        public Builder subagents(List<SubagentDeclaration> declarations) {
            b.subagents(declarations);
            return this;
        }

        public Builder subagentFactory(String name, Function<String, Agent> factory) {
            b.subagentFactory(name, factory);
            return this;
        }

        public Builder taskRepository(TaskRepository taskRepository) {
            b.taskRepository(taskRepository);
            return this;
        }

        public Builder additionalContextFile(String relativePath) {
            b.additionalContextFile(relativePath);
            return this;
        }

        public Builder maxContextTokens(int maxTokens) {
            b.maxContextTokens(maxTokens);
            return this;
        }

        public Builder externalSubagentTool(Object tool) {
            b.externalSubagentTool(tool);
            return this;
        }

        public Builder modelResolver(Function<String, Model> resolver) {
            b.modelResolver(resolver);
            return this;
        }

        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            b.useLegacyXmlWorkspaceContext(enabled);
            return this;
        }

        public List<SubagentEntry> buildSubagentEntries(Path resolvedWorkspace) {
            return b.buildSubagentEntries(resolvedWorkspace);
        }

        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            return b.buildSubagentEntries(resolvedWorkspace, sandboxFs);
        }

        public HarnessAgent build() {
            return new HarnessAgent(b.build());
        }
    }
}
