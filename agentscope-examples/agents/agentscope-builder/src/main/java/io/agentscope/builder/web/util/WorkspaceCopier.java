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
package io.agentscope.builder.web.util;

import io.agentscope.builder.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies every file from one agent's namespaced workspace into another agent's namespaced
 * workspace. Operates over {@link AbstractFilesystem} so it works identically against {@code
 * LocalFilesystem}, {@code SandboxBackedFilesystem}, and other backends.
 *
 * <p>Walks the source namespace with a recursive glob, reads each file's content via the source's
 * namespaced view, then uploads it into the destination's namespaced view. Directories are
 * created implicitly by {@code uploadFiles}; empty directories are not preserved.
 *
 * <p>Activity-log files at the namespace root ({@code activity*.jsonl}) are intentionally
 * excluded so the clone starts with a fresh audit trail.
 */
public final class WorkspaceCopier {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCopier.class);

    private WorkspaceCopier() {}

    /**
     * Copies all files from {@code (srcOwnerId, srcAgentId)} into {@code (dstOwnerId, dstAgentId)}
     * via the supplied factory.
     *
     * @return the number of files copied
     */
    public static int copy(
            WorkspaceManagerFactory factory,
            String srcOwnerId,
            String srcAgentId,
            String dstOwnerId,
            String dstAgentId) {

        WorkspaceManager src = factory.forAgent(srcOwnerId, srcAgentId);
        WorkspaceManager dst = factory.forAgent(dstOwnerId, dstAgentId);
        AbstractFilesystem srcFs = src.getFilesystem();
        AbstractFilesystem dstFs = dst.getFilesystem();
        if (srcFs == null || dstFs == null) {
            throw new IllegalStateException("WorkspaceManager has no filesystem; cannot clone");
        }

        NamespaceFactory srcNs = factory.namespaceFor(srcOwnerId, srcAgentId);
        String srcPrefix = "/" + String.join("/", srcNs.getNamespace());

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
        // Defensive: glob may return paths without leading slash in some backends.
        String trimmedPrefix = srcPrefix.startsWith("/") ? srcPrefix.substring(1) : srcPrefix;
        if (absPath.startsWith(trimmedPrefix + "/")) {
            return absPath.substring(trimmedPrefix.length() + 1);
        }
        return absPath; // assume already relative
    }
}
