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
package io.agentscope.harness.example;

import static io.agentscope.examples.harness.common.util.ExampleUtils.ctx;
import static io.agentscope.examples.harness.common.util.ExampleUtils.runHarnessTurn;
import static io.agentscope.examples.harness.common.util.ExampleUtils.startHarnessChat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Text-to-SQL example using the AgentScope Harness framework.
 *
 * <h2>What this example demonstrates</h2>
 *
 * <ul>
 *   <li>Setting up a <strong>workspace directory</strong> with the standard harness layout
 *       (AGENTS.md, skills/, knowledge/, subagents/)
 *   <li>Wiring a <strong>custom tool</strong> ({@link SqliteTool}) into the agent's toolkit
 *   <li>Building a {@link HarnessAgent} with skills auto-loading, memory tools, and subagents
 *   <li>Calling the agent with a {@link RuntimeContext} (session id, user id)
 *   <li>Reading questions from the console in a loop (optional one-shot mode if you pass a
 *       question as program arguments)
 * </ul>
 *
 * <h2>Prerequisites</h2>
 *
 * <ol>
 *   <li>The Chinook sample database is <strong>bundled</strong> in the JAR under the same package
 *       as this class. If {@code AGENTSCOPE_DB_PATH} (default {@code chinook.db}) does not exist
 *       yet, it is copied there automatically — no manual download is required.
 *   <li>Set the environment variable {@code DASHSCOPE_API_KEY} (or {@code OPENAI_API_KEY} for
 *       OpenAI-compatible endpoints).
 *   <li>Optionally override defaults via the variables listed in {@code .env.example}.
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <p>Run with <strong>no arguments</strong> to start an interactive session: type a question at
 * the {@code >} prompt after startup. Empty line, {@code quit}, {@code exit}, or {@code q} ends
 * the session; EOF (Ctrl-D) also exits.
 *
 * <p>Pass a question as arguments for a <strong>single non-interactive</strong> run (exits after
 * the answer), e.g. for scripts:
 *
 * <pre>
 * java -jar harness-example.jar
 * java -jar harness-example.jar "What are the top 5 best-selling artists?"
 * java -jar harness-example.jar --new-session "What are the top 5 best-selling artists?"
 * </pre>
 *
 * <p>The workspace is initialised under {@code .agentscope/workspace/} relative to the current
 * working directory. Modify the files there to customise the agent's behaviour without recompiling.
 */
public class TextToSqlExample {

    // -------------------------------------------------------------------------
    // Environment variable names
    // -------------------------------------------------------------------------

    /** DashScope API key (required). */
    public static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    /** LLM model name. Defaults to {@code qwen-max}. */
    public static final String ENV_MODEL_NAME = "AGENTSCOPE_MODEL";

    /** Path to the Chinook SQLite database file. Defaults to {@code chinook.db}. */
    public static final String ENV_DB_PATH = "AGENTSCOPE_DB_PATH";

    /** Workspace directory. Defaults to {@code .agentscope/workspace}. */
    public static final String ENV_WORKSPACE = "AGENTSCOPE_WORKSPACE";

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private static final String DEFAULT_MODEL = "qwen-max";
    private static final String DEFAULT_DB_PATH = "chinook.db";
    private static final String DEFAULT_WORKSPACE = ".agentscope/workspace";
    private static final String DEFAULT_SHARED_SESSION_ID = "text-to-sql-shared-default";
    private static final String NEW_SESSION_FLAG = "--new-session";

    /**
     * Bundled Chinook SQLite file (same package on the classpath). Materialised to disk when
     * {@link #ENV_DB_PATH} points to a path that does not exist yet.
     */
    private static final String BUNDLED_CHINOOK_RESOURCE = "chinook-default.sqlite";

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  AgentScope Harness — Text-to-SQL Example");
        System.out.println("═══════════════════════════════════════════════\n");

        // ------------------------------------------------------------------
        // 1. Resolve configuration from environment variables
        // ------------------------------------------------------------------

        String apiKey = requireEnv(ENV_API_KEY);
        String modelName = env(ENV_MODEL_NAME, DEFAULT_MODEL);
        Path workspace = Paths.get(env(ENV_WORKSPACE, DEFAULT_WORKSPACE));
        Path dbPath = resolveDatabasePath(Paths.get(env(ENV_DB_PATH, DEFAULT_DB_PATH)));

        // ------------------------------------------------------------------
        // 2. Initialise workspace from bundled template files
        //    (skips files that already exist — safe to call every run)
        // ------------------------------------------------------------------

        System.out.println("[1/3] Initialising workspace at: " + workspace.toAbsolutePath());
        WorkspaceInitializer.init(workspace);

        // ------------------------------------------------------------------
        // 3. Build the LLM model
        // ------------------------------------------------------------------

        System.out.println("[2/3] Connecting to model: " + modelName);
        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                        .build();

        // ------------------------------------------------------------------
        // 4. Build the agent
        //    - workspace:    loads AGENTS.md, MEMORY.md, knowledge/, subagents/, skills/
        //    - harness:      memory tools, session_search, optional subagent tools
        //    - custom toolkit: SqliteTool is registered alongside harness defaults
        // ------------------------------------------------------------------

        System.out.println("[3/3] Building HarnessAgent ...");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SqliteTool(dbPath));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("text-to-sql")
                        .sysPrompt(
                                "You are a Text-to-SQL agent with access to the Chinook music"
                                        + " store database. When asked a question, explore the"
                                        + " database schema, write a correct SQL query, execute it,"
                                        + " and present the results in a clear, formatted answer.")
                        .model(model)
                        .workspace(workspace)
                        .enableAgentTracingLog(true)
                        .toolkit(toolkit)
                        .build();

        ParsedArgs parsedArgs = parseArgs(args);
        String sessionId =
                parsedArgs.newSession()
                        ? "text-to-sql-" + UUID.randomUUID().toString().substring(0, 8)
                        : DEFAULT_SHARED_SESSION_ID;
        System.out.println("Session ID: " + sessionId);
        RuntimeContext ctx = ctx(sessionId);

        if (parsedArgs.question() != null) {
            runHarnessTurn(agent, ctx, parsedArgs.question());
            return;
        }

        System.out.println(
                "Ask questions in natural language about the Chinook database."
                        + " Same session for all turns (memory tools share context).");
        System.out.println("Tip: add --new-session to generate a UUID-based fresh session.");
        System.out.println("Leave: empty line, quit, exit, q, or EOF (Ctrl-D).\n");
        startHarnessChat(agent, ctx);
    }

    private static ParsedArgs parseArgs(String[] args) {
        boolean newSession = false;
        StringBuilder questionBuilder = new StringBuilder();
        for (String arg : args) {
            if (NEW_SESSION_FLAG.equals(arg)) {
                newSession = true;
                continue;
            }
            if (questionBuilder.length() > 0) {
                questionBuilder.append(' ');
            }
            questionBuilder.append(arg);
        }
        String question = questionBuilder.length() == 0 ? null : questionBuilder.toString();
        return new ParsedArgs(newSession, question);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Uses an existing file at {@code configuredPath}, or copies the bundled Chinook database from
     * the classpath to that path (SQLite itself has no built-in sample data; shipping a small
     * .sqlite in resources gives the same “works out of the box” experience).
     */
    private static Path resolveDatabasePath(Path configuredPath) throws IOException {
        if (Files.exists(configuredPath)) {
            return configuredPath.toAbsolutePath();
        }
        try (InputStream in =
                TextToSqlExample.class.getResourceAsStream(BUNDLED_CHINOOK_RESOURCE)) {
            if (in == null) {
                System.err.println(
                        "Database file not found: "
                                + configuredPath.toAbsolutePath()
                                + "\n\nBuilt-in sample missing from classpath (packaging issue)."
                                + " Download manually:\n"
                                + "  curl -L -o chinook.db \\\n"
                                + "    https://github.com/lerocha/chinook-database/raw/master/"
                                + "ChinookDatabase/DataSources/Chinook_Sqlite.sqlite");
                System.exit(1);
            }
            Path parent = configuredPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, configuredPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(
                    "Materialised bundled Chinook database to: " + configuredPath.toAbsolutePath());
            return configuredPath.toAbsolutePath();
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println(
                    "Required environment variable '"
                            + name
                            + "' is not set.\n"
                            + "Copy .env.example → .env and fill in your API key.");
            System.exit(1);
        }
        return value;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private record ParsedArgs(boolean newSession, String question) {}
}
