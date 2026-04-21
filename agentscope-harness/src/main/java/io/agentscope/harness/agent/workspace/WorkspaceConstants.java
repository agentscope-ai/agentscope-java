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
package io.agentscope.harness.agent.workspace;

/** Path constants for the workspace directory structure. */
public final class WorkspaceConstants {

    private WorkspaceConstants() {}

    public static final String DEFAULT_WORKSPACE_ROOT = ".agentscope/workspace";

    public static final String AGENTS_MD = "AGENTS.md";
    public static final String MEMORY_MD = "MEMORY.md";

    public static final String MEMORY_DIR = "memory";
    public static final String SKILLS_DIR = "skills";
    public static final String KNOWLEDGE_DIR = "knowledge";
    public static final String KNOWLEDGE_MD = "KNOWLEDGE.md";
    public static final String RULES_DIR = "rules";

    public static final String AGENTS_DIR = "agents";
    public static final String SESSIONS_DIR = "sessions";

    /**
     * Per-agent session store filename under {@code agents/&lt;agentId&gt;/sessions/}
     */
    public static final String SESSIONS_STORE = "sessions.json";

    /** JSONL session context file extension (LLM-facing, may be compacted). */
    public static final String SESSION_CONTEXT_EXT = ".jsonl";

    /** JSONL session log file extension (full history, append-only, never compacted). */
    public static final String SESSION_LOG_EXT = ".log.jsonl";

    public static final String SUBAGENT_YML = "subagent.yml";
}
