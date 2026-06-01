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
 * Copies every file from one agent's workspace view into another agent's workspace view. Operates
 * directly on two {@link AbstractFilesystem} instances obtained via {@code
 * HarnessAgent#workspaceFor(userId, null).getFilesystem()}, so it works identically against
 * {@code LocalFilesystem}, {@code RemoteFilesystem}, and the composite filesystem set up by
 * {@link io.agentscope.builder.web.config.BuilderConfig}.
 *
 * <p>Walks the source view with a recursive {@code **&#47;*} glob, reads each matched file's
 * content, and uploads it into the destination view. Directories are created implicitly by
 * {@code uploadFiles}; empty directories are not preserved.
 *
 * <p>Activity-log files under {@code activity/} are intentionally excluded so the clone starts
 * with a fresh audit trail.
 */
public final class WorkspaceCopier {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCopier.class);

    private WorkspaceCopier() {}

    /**
     * Copies every user-data file from {@code srcFs} into {@code dstFs}. Only the routed/user-data
     * layer is copied — shared workspace content (AGENTS.md, tools.json, shared skills/, ...) that
     * lives on the read-only local layer is not duplicated per agent.
     *
     * @param srcFs source workspace view (typically {@code srcAgent.workspaceFor(srcOwner,
     *     null).getFilesystem()})
     * @param dstFs destination workspace view (typically {@code dstAgent.workspaceFor(dstOwner,
     *     null).getFilesystem()})
     * @param srcLabel human-readable source label used only for log messages (e.g.
     *     {@code "userA/agent-x"})
     * @param dstLabel human-readable destination label used only for log messages
     * @return the number of files copied
     */
    public static int copy(
            AbstractFilesystem srcFs, AbstractFilesystem dstFs, String srcLabel, String dstLabel) {

        GlobResult globRes = srcFs.glob(null, "**/*", null);
        if (!globRes.isSuccess() || globRes.matches() == null) {
            log.info("Nothing to copy from {}", srcLabel);
            return 0;
        }

        List<Map.Entry<String, byte[]>> uploads = new ArrayList<>();
        int skipped = 0;
        for (FileInfo info : globRes.matches()) {
            if (info.isDirectory()) continue;
            String rel = normalize(info.path());
            if (rel == null || rel.isBlank()) continue;
            // Excludes both legacy root-level (pre-PR4) and current activity/ layout.
            if (rel.startsWith("activity/")
                    || (rel.startsWith("activity") && rel.endsWith(".jsonl"))) {
                skipped++;
                continue;
            }
            ReadResult rr = srcFs.read(null, rel, 0, Integer.MAX_VALUE);
            if (!rr.isSuccess() || rr.fileData() == null) {
                log.warn("Skipping file during clone (read failed): {} -> {}", rel, rr.error());
                continue;
            }
            String content = rr.fileData().content();
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            uploads.add(new AbstractMap.SimpleEntry<>(rel, bytes));
        }

        if (uploads.isEmpty()) {
            log.info(
                    "Clone {} -> {}: source workspace empty (skipped {} audit files)",
                    srcLabel,
                    dstLabel,
                    skipped);
            return 0;
        }

        dstFs.uploadFiles(null, uploads);
        log.info(
                "Cloned {} files from {} to {} (skipped {} audit files)",
                uploads.size(),
                srcLabel,
                dstLabel,
                skipped);
        return uploads.size();
    }

    /** Trim any leading slash so the path can be reused unchanged for read and upload. */
    private static String normalize(String path) {
        if (path == null) return null;
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
