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
package io.agentscope.dataagent.web.util;

import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies every file from one agent's per-user workspace into another's. Operates over
 * {@link AbstractFilesystem} so the same code works against any backing implementation — today
 * that is the per-{@code (userId, agentId)} sandbox view supplied by
 * {@link WorkspaceManagerFactory#userDataFs}.
 *
 * <p>Walks the source with a recursive glob, reads each file, then uploads it into the
 * destination. Directories are created implicitly by {@code uploadFiles}; empty directories are
 * not preserved.
 *
 * <p>Activity-log files at the source root ({@code activity*.jsonl}) are intentionally excluded so
 * the clone starts with a fresh audit trail.
 */
public final class WorkspaceCopier {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCopier.class);

    private WorkspaceCopier() {}

    /**
     * Copies every user-data file from {@code (srcOwnerId, srcAgentId)} into {@code (dstOwnerId,
     * dstAgentId)} via the supplied factory. Only the user-isolated layer is copied — shared
     * workspace content (AGENTS.md, tools.json, shared skills/, ...) lives once at the workspace
     * root and is not duplicated per agent.
     *
     * @param srcWorkspacePath user-supplied workspace path of the source agent (may be null)
     * @param dstWorkspacePath user-supplied workspace path of the target agent (may be null)
     * @return the number of files copied
     */
    public static int copy(
            WorkspaceManagerFactory factory,
            String srcOwnerId,
            String srcAgentId,
            String srcWorkspacePath,
            String dstOwnerId,
            String dstAgentId,
            String dstWorkspacePath) {

        AbstractFilesystem srcFs = factory.userDataFs(srcOwnerId, srcAgentId, srcWorkspacePath);
        AbstractFilesystem dstFs = factory.userDataFs(dstOwnerId, dstAgentId, dstWorkspacePath);

        String srcPrefix = factory.userDataPathPrefix(srcOwnerId, srcAgentId, srcWorkspacePath);

        GlobResult globRes = srcFs.glob(null, "**/*", null);
        if (!globRes.isSuccess() || globRes.matches() == null) {
            log.info("Nothing to copy from {}/{}", srcOwnerId, srcAgentId);
            return 0;
        }

        List<Map.Entry<String, byte[]>> uploads = new ArrayList<>();
        int skipped = 0;
        for (FileInfo info : globRes.matches()) {
            if (info.isDirectory()) continue;
            String absPath = info.path();
            String rel = stripPrefix(absPath, srcPrefix);
            if (rel == null || rel.isBlank()) continue;
            if (rel.startsWith("activity") && rel.endsWith(".jsonl")) {
                skipped++;
                continue;
            }
            ReadResult rr = srcFs.read(null, rel, 0, Integer.MAX_VALUE);
            if (!rr.isSuccess() || rr.fileData() == null) {
                log.warn("Skipping file during clone (read failed): {} -> {}", absPath, rr.error());
                continue;
            }
            String content = rr.fileData().content();
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            uploads.add(new AbstractMap.SimpleEntry<>(rel, bytes));
        }

        if (uploads.isEmpty()) {
            log.info(
                    "Clone {}/{} -> {}/{}: source workspace empty (skipped {} audit files)",
                    srcOwnerId,
                    srcAgentId,
                    dstOwnerId,
                    dstAgentId,
                    skipped);
            return 0;
        }

        dstFs.uploadFiles(null, uploads);
        log.info(
                "Cloned {} files from {}/{} to {}/{} (skipped {} audit files)",
                uploads.size(),
                srcOwnerId,
                srcAgentId,
                dstOwnerId,
                dstAgentId,
                skipped);
        return uploads.size();
    }

    private static String stripPrefix(String absPath, String srcPrefix) {
        if (absPath == null) return null;
        if (absPath.startsWith(srcPrefix + "/")) {
            return absPath.substring(srcPrefix.length() + 1);
        }
        if (absPath.equals(srcPrefix)) return "";
        // Defensive: glob may return paths without leading slash in some stores.
        String trimmedPrefix = srcPrefix.startsWith("/") ? srcPrefix.substring(1) : srcPrefix;
        if (absPath.startsWith(trimmedPrefix + "/")) {
            return absPath.substring(trimmedPrefix.length() + 1);
        }
        return absPath; // assume already relative
    }
}
