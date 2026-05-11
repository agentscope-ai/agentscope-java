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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for searching past session transcripts and viewing session history.
 *
 * <p>Operates exclusively on the local session cache. Remote synchronisation is handled by
 * {@link io.agentscope.harness.agent.memory.session.SessionTree#load()} in write paths
 * (e.g. {@link io.agentscope.harness.agent.memory.MemoryFlushManager}), keeping this tool
 * lightweight and fast for in-process search.
 */
public class SessionSearchTool {

    private static final Logger log = LoggerFactory.getLogger(SessionSearchTool.class);

    private final WorkspaceManager workspaceManager;

    public SessionSearchTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Tool(
            name = "session_search",
            description =
                    "Search past session transcripts for a keyword or phrase."
                            + " Returns matching entries with session context.")
    public String sessionSearch(
            @ToolParam(name = "query", description = "Search query (keyword or phrase)")
                    String query,
            @ToolParam(
                            name = "agentId",
                            description = "Agent ID to search sessions for",
                            required = false)
                    String agentId,
            @ToolParam(
                            name = "maxResults",
                            description = "Maximum number of results to return (default: 10)",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "Error: query is required";
        }

        int limit = maxResults != null && maxResults > 0 ? maxResults : 10;
        String effectiveAgentId = agentId != null && !agentId.isBlank() ? agentId : null;
        String lowerQuery = query.toLowerCase();

        List<String> results = new ArrayList<>();

        List<Path> sessionFiles = listLogFiles(effectiveAgentId);
        for (Path file : sessionFiles) {
            if (results.size() >= limit) {
                break;
            }
            searchInSessionFile(file, lowerQuery, results, limit);
        }

        if (results.isEmpty()) {
            return "No matches found for: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d matches for \"%s\":\n\n", results.size(), query));
        for (String result : results) {
            sb.append(result).append("\n");
        }
        return sb.toString();
    }

    @Tool(
            name = "session_list",
            description = "List available sessions for an agent, showing session IDs and metadata.")
    public String sessionList(
            @ToolParam(name = "agentId", description = "Agent ID to list sessions for")
                    String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "Error: agentId is required";
        }

        // Prefer the structured session-store index (already two-layer: remote then local).
        String storeContent =
                workspaceManager.readManagedWorkspaceFileUtf8(
                        WorkspaceConstants.AGENTS_DIR
                                + "/"
                                + agentId
                                + "/"
                                + WorkspaceConstants.SESSIONS_DIR
                                + "/"
                                + WorkspaceConstants.SESSIONS_STORE);
        if (!storeContent.isBlank()) {
            return storeContent;
        }

        // List sessions from local cache only — remote sync is handled at write time.
        Path sessionDir = workspaceManager.getSessionDir(agentId);
        if (!Files.isDirectory(sessionDir)) {
            return "No sessions found for agent: " + agentId;
        }

        List<Path> sessionFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.list(sessionDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(
                            p ->
                                    p.getFileName()
                                            .toString()
                                            .endsWith(WorkspaceConstants.SESSION_CONTEXT_EXT))
                    .forEach(sessionFiles::add);
        } catch (IOException e) {
            log.debug("Could not list local session dir for agent {}: {}", agentId, e.getMessage());
        }

        if (sessionFiles.isEmpty()) {
            return "No sessions found for agent: " + agentId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sessions for agent ").append(agentId).append(":\n");
        for (Path file : sessionFiles) {
            String name = file.getFileName().toString();
            String sessionId = name.replace(WorkspaceConstants.SESSION_CONTEXT_EXT, "");
            sb.append("  - ").append(sessionId).append("\n");
        }
        return sb.toString();
    }

    @Tool(
            name = "session_history",
            description =
                    "Get the conversation history for a specific session."
                            + " Returns the messages in the session.")
    public String sessionHistory(
            @ToolParam(name = "agentId", description = "Agent ID") String agentId,
            @ToolParam(name = "sessionId", description = "Session ID") String sessionId,
            @ToolParam(
                            name = "lastN",
                            description = "Number of recent messages to return (default: 20)",
                            required = false)
                    Integer lastN) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "Error: agentId and sessionId are required";
        }

        int limit = lastN != null && lastN > 0 ? lastN : 20;

        Path contextFile = workspaceManager.resolveSessionContextFile(agentId, sessionId);
        if (!Files.isRegularFile(contextFile)) {
            @SuppressWarnings("deprecation")
            Path legacyFile = workspaceManager.resolveSessionFile(agentId, sessionId);
            if (Files.isRegularFile(legacyFile)) {
                log.debug("Falling back to legacy .json session file for {}", sessionId);
                return readLegacySession(legacyFile, limit);
            }
            return "Session not found: " + sessionId;
        }

        SessionTree tree = new SessionTree(contextFile, workspaceManager.getWorkspace(), null);
        tree.load();

        List<SessionEntry.MessageEntry> messages = tree.getMessageEntries();
        int start = Math.max(0, messages.size() - limit);

        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "Session %s (%d total messages, showing last %d):\n\n",
                        sessionId, messages.size(), Math.min(limit, messages.size())));
        for (int i = start; i < messages.size(); i++) {
            SessionEntry.MessageEntry msg = messages.get(i);
            String content = msg.getContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "... [truncated]";
            }
            sb.append(String.format("[%s]: %s\n", msg.getRole(), content));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Collects all {@code .log.jsonl} files under the sessions directory for the given agent
     * (or all agents when {@code agentId} is {@code null}).
     * Only scans the local disk; remote-only sessions are handled via sessionList / sessionHistory.
     */
    private List<Path> listLogFiles(String agentId) {
        List<Path> files = new ArrayList<>();
        Path agentsDir = workspaceManager.getWorkspace().resolve(WorkspaceConstants.AGENTS_DIR);
        if (!Files.isDirectory(agentsDir)) {
            return files;
        }

        if (agentId != null) {
            Path sessionDir = agentsDir.resolve(agentId).resolve(WorkspaceConstants.SESSIONS_DIR);
            collectLogFiles(sessionDir, files);
            return files;
        }

        try (Stream<Path> walk = Files.list(agentsDir)) {
            walk.filter(Files::isDirectory)
                    .forEach(
                            agentDir ->
                                    collectLogFiles(
                                            agentDir.resolve(WorkspaceConstants.SESSIONS_DIR),
                                            files));
        } catch (IOException e) {
            // ignore
        }
        return files;
    }

    private void collectLogFiles(Path sessionDir, List<Path> collector) {
        if (!Files.isDirectory(sessionDir)) {
            return;
        }
        try (Stream<Path> walk = Files.list(sessionDir)) {
            walk.filter(p -> p.toString().endsWith(WorkspaceConstants.SESSION_LOG_EXT))
                    .filter(Files::isRegularFile)
                    .forEach(collector::add);
        } catch (IOException e) {
            // ignore
        }
    }

    private void searchInSessionFile(
            Path logFile, String lowerQuery, List<String> results, int limit) {
        try {
            Path contextFile =
                    logFile.resolveSibling(
                            logFile.getFileName()
                                    .toString()
                                    .replace(
                                            WorkspaceConstants.SESSION_LOG_EXT,
                                            WorkspaceConstants.SESSION_CONTEXT_EXT));
            SessionTree tree = new SessionTree(contextFile, workspaceManager.getWorkspace(), null);
            tree.load();

            String relPath = workspaceManager.getWorkspace().relativize(logFile).toString();
            for (SessionEntry.MessageEntry msg : tree.getMessageEntries()) {
                if (results.size() >= limit) {
                    break;
                }
                String content = msg.getContent();
                if (content != null && content.toLowerCase().contains(lowerQuery)) {
                    String preview =
                            content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    results.add(
                            String.format(
                                    "  [%s] %s — [%s]: %s",
                                    relPath, msg.getId(), msg.getRole(), preview));
                }
            }
        } catch (Exception e) {
            // skip corrupted files
        }
    }

    private String readLegacySession(Path file, int limit) {
        try {
            String content = Files.readString(file);
            String[] lines = content.split("\n");
            int start = Math.max(0, lines.length - limit);
            StringBuilder sb = new StringBuilder();
            sb.append(
                    String.format(
                            "Legacy session (%d lines, showing last %d):\n",
                            lines.length, Math.min(limit, lines.length)));
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Error reading session file: " + e.getMessage();
        }
    }
}
