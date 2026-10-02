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
package io.agentscope.claw2.web.config;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.aistio.adapter.AgentRuntimeSource;
import io.agentscope.extensions.aistio.model.Inventory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harness-backed {@link AgentRuntimeSource} for paw: subagent/workspace inventory, background
 * tasks, plan excerpts, and Definition workspace summary.
 */
public final class HarnessAgentRuntimeSource implements AgentRuntimeSource {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentRuntimeSource.class);
    private static final int PLAN_EXCERPT_MAX = 4000;

    private final HarnessAgent harness;

    public HarnessAgentRuntimeSource(HarnessAgent harness) {
        this.harness = harness;
    }

    @Override
    public List<Inventory.SubagentInfo> listSubagents(Agent agent) {
        WorkspaceManager wm = harness.getWorkspaceManager();
        if (wm == null) {
            return List.of();
        }
        Path root = wm.getWorkspace();
        AbstractFilesystem fs = wm.getFilesystem();
        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromFilesystem(fs, root == null ? Path.of(".") : root);
        List<Inventory.SubagentInfo> out = new ArrayList<>(decls.size());
        for (SubagentDeclaration d : decls) {
            out.add(
                    new Inventory.SubagentInfo(
                            d.getName(),
                            d.getDescription(),
                            d.getTools() == null ? List.of() : List.copyOf(d.getTools()),
                            d.getWorkspaceMode() == null ? "" : d.getWorkspaceMode().name(),
                            d.getUrl() == null ? "" : d.getUrl(),
                            0L,
                            0L));
        }
        return out;
    }

    @Override
    public List<Inventory.WorkspaceInfo> listWorkspaces(Agent agent) {
        WorkspaceManager wm = harness.getWorkspaceManager();
        if (wm == null) {
            return List.of();
        }
        Path root = wm.getWorkspace();
        String path = root == null ? "" : root.toAbsolutePath().normalize().toString();
        long size = 0L;
        if (root != null) {
            try {
                size = Files.exists(root) ? directorySize(root) : 0L;
            } catch (Exception e) {
                log.debug("aistio: workspace size failed: {}", e.getMessage());
            }
        }
        return List.of(new Inventory.WorkspaceInfo(path, "primary", size, harness.getName()));
    }

    @Override
    public List<Map<String, Object>> listSubagentTasks(
            Agent agent, String sessionId, String userId) {
        TaskRepository repo = harness.getTaskRepository();
        if (repo == null) {
            return List.of();
        }
        RuntimeContext rc = RuntimeContext.empty();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BackgroundTask task : repo.listTasks(rc, sessionId, null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getTaskId());
            row.put("taskId", task.getTaskId());
            row.put("subagentId", task.getAgentId());
            row.put("status", task.getTaskStatus() == null ? "" : task.getTaskStatus().name());
            row.put("subject", task.getAgentId());
            if (task.getCreatedAt() != null) {
                row.put("createdAt", task.getCreatedAt().toString());
            }
            if (task.getLastCheckedAt() != null) {
                row.put("lastCheckedAt", task.getLastCheckedAt().toString());
            }
            row.put("completed", task.isCompleted());
            out.add(row);
        }
        return out;
    }

    @Override
    public boolean cancelSubagentTask(Agent agent, String sessionId, String userId, String taskId) {
        TaskRepository repo = harness.getTaskRepository();
        if (repo == null) {
            return false;
        }
        return repo.cancelTask(RuntimeContext.empty(), sessionId, taskId);
    }

    @Override
    public void enrichAgentConfig(Agent agent, Map<String, Object> agentConfig) {
        WorkspaceManager wm = harness.getWorkspaceManager();
        if (wm == null) {
            return;
        }
        Path root = wm.getWorkspace();
        AbstractFilesystem fs = wm.getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();

        List<Map<String, Object>> skills = listSkillNames(fs, rc);
        if (!skills.isEmpty()) {
            agentConfig.put("skills", skills);
        }

        List<Inventory.SubagentInfo> subs = listSubagents(agent);
        if (!subs.isEmpty()) {
            List<Map<String, Object>> subRows = new ArrayList<>(subs.size());
            for (Inventory.SubagentInfo s : subs) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", s.name());
                if (s.description() != null && !s.description().isEmpty()) {
                    row.put("description", s.description());
                }
                if (s.workspaceMode() != null && !s.workspaceMode().isEmpty()) {
                    row.put("workspaceMode", s.workspaceMode());
                }
                subRows.add(row);
            }
            agentConfig.put("subagents", subRows);
        }

        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("hasAgentsMd", exists(fs, rc, "AGENTS.md"));
        workspace.put("hasToolsJson", exists(fs, rc, "tools.json"));
        workspace.put("hasMemoryMd", exists(fs, rc, "MEMORY.md"));
        workspace.put("skillCount", skills.size());
        workspace.put("subagentCount", subs.size());
        if (root != null) {
            workspace.put("path", root.toAbsolutePath().normalize().toString());
        }
        agentConfig.put("workspace", workspace);

        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) agentConfig.get("sources");
        if (sources == null) {
            agentConfig.put("sources", List.of("builder", "workspace"));
        } else if (!sources.contains("workspace")) {
            List<String> next = new ArrayList<>(sources);
            next.add("workspace");
            agentConfig.put("sources", next);
        }
    }

    @Override
    public Optional<String> readPlanExcerpt(
            Agent agent, String sessionId, String userId, String planFile) {
        if (planFile == null || planFile.isBlank()) {
            return Optional.empty();
        }
        WorkspaceManager wm =
                userId != null && sessionId != null
                        ? harness.workspaceFor(userId, sessionId)
                        : harness.getWorkspaceManager();
        if (wm == null) {
            return Optional.empty();
        }
        try {
            ReadResult rr =
                    wm.getFilesystem().read(RuntimeContext.empty(), planFile, 0, PLAN_EXCERPT_MAX);
            if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                return Optional.empty();
            }
            String content = rr.fileData().content();
            if (content.length() > PLAN_EXCERPT_MAX) {
                content = content.substring(0, PLAN_EXCERPT_MAX) + "…";
            }
            return Optional.of(content);
        } catch (Exception e) {
            log.debug("aistio: plan excerpt read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean setPlanMode(Agent agent, String sessionId, String userId, boolean active) {
        if (active) {
            harness.enterPlanMode(userId, sessionId);
        } else {
            harness.exitPlanMode(userId, sessionId);
        }
        return true;
    }

    private static List<Map<String, Object>> listSkillNames(
            AbstractFilesystem fs, RuntimeContext rc) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            GlobResult glob = fs.glob(rc, "**/SKILL.md", "skills");
            if (!glob.isSuccess() || glob.matches() == null) {
                return out;
            }
            for (FileInfo fi : glob.matches()) {
                String path = fi.path();
                if (path == null) {
                    continue;
                }
                // skills/<name>/SKILL.md
                String[] parts = path.replace('\\', '/').split("/");
                String name = null;
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("skills".equals(parts[i]) && i + 1 < parts.length) {
                        name = parts[i + 1];
                        break;
                    }
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                out.add(Map.of("name", name));
            }
        } catch (Exception e) {
            log.debug("aistio: skill list failed: {}", e.getMessage());
        }
        return out;
    }

    private static boolean exists(AbstractFilesystem fs, RuntimeContext rc, String path) {
        try {
            LsResult parent = fs.ls(rc, parentDir(path));
            if (!parent.isSuccess() || parent.entries() == null) {
                ReadResult rr = fs.read(rc, path, 0, 1);
                return rr.isSuccess();
            }
            String want = fileName(path);
            for (FileInfo fi : parent.entries()) {
                if (want.equals(fileName(fi.path()))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String parentDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    private static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    private static long directorySize(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(
                            p -> {
                                try {
                                    return Files.size(p);
                                } catch (Exception e) {
                                    return 0L;
                                }
                            })
                    .sum();
        }
    }
}
