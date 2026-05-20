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
package io.agentscope.examples.harness.remote;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.harness.remote.data.SqliteTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Data Agent using {@link RemoteFilesystemSpec} — the composite + shared-store mode described in
 * {@code docs/zh/harness/filesystem.md} as <strong>模式一</strong> (and in {@link
 * io.agentscope.harness.agent.HarnessAgent.Builder#filesystem(RemoteFilesystemSpec)} as
 * <strong>Mode 1</strong>).
 *
 * <p><strong>Not</strong> sandbox mode: there is no {@code ShellExecuteTool}. For <strong>模式二
 * / Mode 2</strong> (sandbox + shell in isolation), use {@code harness-example-sandbox}.
 *
 * <p>Shared components (simulate Redis + multi-replica in one JVM):
 *
 * <ul>
 *   <li>{@link InMemoryStore} — {@link io.agentscope.harness.agent.store.BaseStore} for
 *       MEMORY.md, memory/, session paths, and {@code knowledge/}
 *   <li>{@link InMemorySession} — non-local {@link Session} required by Harness when using remote
 *       filesystem spec (production would use RedisSession, etc.)
 * </ul>
 */
@Service
public class DataAgentService {

    private static final Logger log = LoggerFactory.getLogger(DataAgentService.class);

    private static final String AGENT_NAME = "data-agent";
    private static final String BUNDLED_CHINOOK_RESOURCE = "chinook-default.sqlite";
    private static final String SYS_PROMPT =
            "You are a Text-to-SQL agent with access to the Chinook music store database."
                    + " Use the sql_* tools. This deployment has no shell tool — use read_file,"
                    + " write_file, grep_files for workspace files. Answer clearly.";

    private Path hostWorkspace;
    private Path dbPath;
    private String llmModelId;
    private RemoteFilesystemSpec remoteSpec;
    private Session appSession;

    @PostConstruct
    void init() throws Exception {
        hostWorkspace = WorkspaceClasspathMaterializer.materialize();
        dbPath = materialiseChinook(hostWorkspace.resolve("chinook.db"));
        llmModelId = resolveLlmModelId();

        InMemoryStore store = new InMemoryStore();
        remoteSpec =
                new RemoteFilesystemSpec(store)
                        .isolationScope(IsolationScope.USER)
                        .addSharedPrefix("knowledge/");
        appSession = new InMemorySession();

        log.info(
                "DataAgentService ready (RemoteFilesystemSpec): workspace={} db={}",
                hostWorkspace,
                dbPath);
    }

    public String query(String sessionId, String userId, String question) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqliteTool(dbPath));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name(AGENT_NAME)
                        .model(llmModelId)
                        .workspace(hostWorkspace)
                        .filesystem(remoteSpec)
                        .session(appSession)
                        .sysPrompt(SYS_PROMPT)
                        .toolkit(toolkit)
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
