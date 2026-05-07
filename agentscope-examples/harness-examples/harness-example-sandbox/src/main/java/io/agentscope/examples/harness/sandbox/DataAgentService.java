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
package io.agentscope.examples.harness.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.harness.sandbox.data.SqliteTool;
import io.agentscope.examples.harness.sandbox.support.InMemorySandboxClient;
import io.agentscope.examples.harness.sandbox.support.InMemorySandboxFilesystemSpec;
import io.agentscope.examples.harness.sandbox.support.SharedInMemorySandboxStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Data Agent service: Chinook text-to-SQL with sandbox isolation.
 *
 * <p>Infrastructure shared across all requests (initialized once on startup):
 *
 * <ul>
 *   <li>{@link InMemorySandboxClient} — local-process sandbox (stands in for Docker in
 *       production)
 *   <li>{@link SharedInMemorySandboxStateStore} — shared state store (stands in for Redis)
 *   <li>{@link InMemorySandboxFilesystemSpec} with {@link IsolationScope#USER} — each unique
 *       {@code userId} gets its own sandbox; the sandbox persists across sessions for that user
 * </ul>
 *
 * <p>A fresh {@link HarnessAgent} is built per request so concurrent calls with different
 * {@code userId} values do not interfere. The heavy shared state (sandbox instance, workspace
 * files, memory) lives in the stores above, not in the agent object itself.
 *
 * <p>Required environment variables:
 *
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} — DashScope API key
 *   <li>{@code AGENTSCOPE_MODEL} — model name (default: {@code qwen-max})
 * </ul>
 */
@Service
public class DataAgentService {

    private static final Logger log = LoggerFactory.getLogger(DataAgentService.class);

    private static final String AGENT_NAME = "data-agent";
    private static final String BUNDLED_CHINOOK_RESOURCE = "chinook-default.sqlite";
    private static final String SYS_PROMPT =
            "You are a Text-to-SQL agent with access to the Chinook music store database."
                    + " Use the sql_* tools to explore the schema and run read-only SELECT queries."
                    + " Follow the query-writing skill and answer clearly in plain language.";

    private Path hostWorkspace;
    private Path dbPath;
    private String llmModelId;
    private InMemorySandboxFilesystemSpec fsSpec;
    private SharedInMemorySandboxStateStore stateStore;

    @PostConstruct
    void init() throws Exception {
        hostWorkspace = WorkspaceClasspathMaterializer.materialize();
        dbPath = materialiseChinook(hostWorkspace.resolve("chinook.db"));
        llmModelId = resolveLlmModelId();

        stateStore = new SharedInMemorySandboxStateStore();
        fsSpec = new InMemorySandboxFilesystemSpec(new InMemorySandboxClient());
        fsSpec.isolationScope(IsolationScope.USER).sandboxStateStore(stateStore);

        log.info("DataAgentService ready: workspace={} db={}", hostWorkspace, dbPath);
    }

    /**
     * Processes a user question with the data agent.
     *
     * <ul>
     *   <li>{@code sessionId} — scopes the conversation history (same session = same chat context)
     *   <li>{@code userId} — scopes the sandbox workspace and memory (same user = shared sandbox
     *       and MEMORY.md across sessions)
     * </ul>
     *
     * @param sessionId session identifier
     * @param userId user identifier
     * @param question natural-language question about the Chinook database
     * @return agent answer
     */
    public String query(String sessionId, String userId, String question) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqliteTool(dbPath));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name(AGENT_NAME)
                        .model(llmModelId)
                        .workspace(hostWorkspace)
                        .filesystem(fsSpec)
                        .sysPrompt(SYS_PROMPT)
                        .toolkit(toolkit)
                        .sandboxDistributed(
                                SandboxDistributedOptions.builder()
                                        .requireDistributed(false)
                                        .build())
                        .enableAgentTracingLog(true)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(question).build();
        Msg reply = agent.call(userMsg, ctx).block();
        return reply != null ? reply.getTextContent() : "(no response)";
    }

    private String resolveLlmModelId() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable DASHSCOPE_API_KEY is not set.");
        }
        String modelName = envOrDefault("AGENTSCOPE_MODEL", "qwen-max");
        String id = "dashscope:" + modelName;
        log.info("Using model: {}", id);
        return id;
    }

    private static Path materialiseChinook(Path target) throws Exception {
        if (Files.exists(target)) {
            return target.toAbsolutePath();
        }
        try (InputStream in =
                DataAgentService.class.getResourceAsStream(BUNDLED_CHINOOK_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Bundled Chinook DB not found on classpath: " + BUNDLED_CHINOOK_RESOURCE);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
