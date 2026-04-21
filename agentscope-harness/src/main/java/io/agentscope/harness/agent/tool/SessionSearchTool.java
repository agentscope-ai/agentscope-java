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

        List<Path> sessionDirs = listSessionDirs(effectiveAgentId);
        for (Path sessionDir : sessionDirs) {
            List<Path> sessionFiles = listJsonlFiles(sessionDir);
            for (Path file : sessionFiles) {
                if (results.size() >= limit) {
                    break;
                }
                searchInSessionFile(file, lowerQuery, results, limit);
            }
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

        Path sessionDir = workspaceManager.getSessionDir(agentId);
        if (!Files.isDirectory(sessionDir)) {
            return "No sessions found for agent: " + agentId;
        }

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

        List<Path> sessionFiles = listJsonlFiles(sessionDir);
        if (sessionFiles.isEmpty()) {
            return "No sessions found for agent: " + agentId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sessions for agent ").append(agentId).append(":\n");
        for (Path file : sessionFiles) {
            String name = file.getFileName().toString();
            String sessionId =
                    name.replace(WorkspaceConstants.SESSION_CONTEXT_EXT, "")
                            .replace(WorkspaceConstants.SESSION_LOG_EXT, "")
                            .replace(".json", "");
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

        SessionTree tree = new SessionTree(contextFile);
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

    private List<Path> listSessionDirs(String agentId) {
        List<Path> dirs = new ArrayList<>();
        Path agentsDir = workspaceManager.getWorkspace().resolve(WorkspaceConstants.AGENTS_DIR);
        if (!Files.isDirectory(agentsDir)) {
            return dirs;
        }

        if (agentId != null) {
            Path dir = agentsDir.resolve(agentId).resolve(WorkspaceConstants.SESSIONS_DIR);
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
            return dirs;
        }

        try (Stream<Path> walk = Files.list(agentsDir)) {
            walk.filter(Files::isDirectory)
                    .forEach(
                            agentDir -> {
                                Path sessDir = agentDir.resolve(WorkspaceConstants.SESSIONS_DIR);
                                if (Files.isDirectory(sessDir)) {
                                    dirs.add(sessDir);
                                }
                            });
        } catch (IOException e) {
            // ignore
        }
        return dirs;
    }

    private List<Path> listJsonlFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(p -> p.toString().endsWith(".log.jsonl"))
                    .filter(Files::isRegularFile)
                    .forEach(files::add);
        } catch (IOException e) {
            // ignore
        }
        return files;
    }

    private void searchInSessionFile(
            Path file, String lowerQuery, List<String> results, int limit) {
        try {
            SessionTree tree =
                    new SessionTree(
                            file.resolveSibling(
                                    file.getFileName().toString().replace(".log.jsonl", ".jsonl")));
            tree.load();

            String relPath = workspaceManager.getWorkspace().relativize(file).toString();
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
