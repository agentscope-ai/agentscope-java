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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit log for the skill self-learning loop. Every {@code skill_manage} write,
 * promotion decision, and curator transition lands as one JSON line in
 * {@code workspace/skills/.audit/YYYY-MM-DD.jsonl}.
 *
 * <p>This is intentionally separate from OTel / trace spans (which other code paths emit
 * concurrently). The on-disk JSONL is the recovery / forensic copy: it survives OTel
 * collector outages and gives operators a queryable record without standing up a metrics
 * stack. {@code agent.queryAudit(predicate)} reads back every entry from the latest day file.
 */
public class SkillAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SkillAuditLog.class);

    public static final String AUDIT_DIR = "skills/.audit";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final AbstractFilesystem filesystem;
    private final WorkspaceManager workspaceManager;
    private final ReentrantLock lock = new ReentrantLock();

    public SkillAuditLog(AbstractFilesystem filesystem, WorkspaceManager workspaceManager) {
        this.filesystem = filesystem;
        this.workspaceManager = workspaceManager;
    }

    /** A single audit row. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            Instant ts,
            String actor,
            String op,
            String target,
            String decision,
            String scanVerdict,
            String beforeHash,
            String afterHash,
            Map<String, String> ctx,
            Map<String, Object> extra) {}

    /** Append a new entry. Best-effort: failures are logged but never thrown. */
    public void append(Entry entry) {
        if (entry == null) {
            return;
        }
        Entry stamped =
                entry.ts() != null
                        ? entry
                        : new Entry(
                                Instant.now(),
                                entry.actor(),
                                entry.op(),
                                entry.target(),
                                entry.decision(),
                                entry.scanVerdict(),
                                entry.beforeHash(),
                                entry.afterHash(),
                                entry.ctx(),
                                entry.extra());
        String day = DAY_FMT.format(stamped.ts());
        String path = AUDIT_DIR + "/" + day + ".jsonl";
        lock.lock();
        try {
            String json = JSON.writeValueAsString(stamped);
            String content = json + "\n";
            // Use WorkspaceManager's append helper so we stay on the standard write path.
            if (workspaceManager != null) {
                workspaceManager.appendUtf8WorkspaceRelative(RuntimeContext.empty(), path, content);
            } else if (filesystem != null) {
                // Fallback when no WorkspaceManager — read existing then upload concatenated.
                String existing = "";
                try {
                    var rr = filesystem.read(RuntimeContext.empty(), path, 0, 0);
                    if (rr.isSuccess()
                            && rr.fileData() != null
                            && rr.fileData().content() != null) {
                        existing = rr.fileData().content();
                    }
                } catch (Exception ignored) {
                }
                filesystem.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(
                                Map.entry(
                                        path,
                                        (existing + content).getBytes(StandardCharsets.UTF_8))));
            }
        } catch (Exception e) {
            log.warn("SkillAuditLog.append() failed: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Read the audit log for a given day, parse every line into {@link Entry}, then filter via
     * the predicate. Pass {@code null} for {@code dayUtc} to query "today".
     */
    public List<Entry> query(String dayUtc, Predicate<Entry> filter) {
        if (filesystem == null) {
            return List.of();
        }
        if (dayUtc == null || dayUtc.isBlank()) {
            dayUtc = DAY_FMT.format(Instant.now());
        }
        String path = AUDIT_DIR + "/" + dayUtc + ".jsonl";
        try {
            var rr = filesystem.read(RuntimeContext.empty(), path, 0, 0);
            if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                return List.of();
            }
            List<Entry> out = new ArrayList<>();
            for (String line : rr.fileData().content().split("\n")) {
                if (line.isBlank()) continue;
                try {
                    Entry e = JSON.readValue(line, Entry.class);
                    if (filter == null || filter.test(e)) {
                        out.add(e);
                    }
                } catch (Exception parseError) {
                    // Skip malformed lines but keep going.
                    log.debug("audit line parse error: {}", parseError.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("query() failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** Convenience builder for the common {@code skill_manage} entry shape. */
    public static Entry manageEntry(
            String actor, String name, String action, String draftOrMain, String verdict) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("target_dir", draftOrMain);
        return new Entry(
                Instant.now(), actor, "manage", name, action, verdict, null, null, ctx, null);
    }

    /** Convenience builder for the {@code promote} audit entry. */
    public static Entry promoteEntry(
            String reviewer,
            String name,
            String decision,
            String scanVerdict,
            List<String> environments) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (environments != null) {
            extra.put("environments", environments);
        }
        return new Entry(
                Instant.now(),
                reviewer,
                "promote",
                name,
                decision,
                scanVerdict,
                null,
                null,
                null,
                extra);
    }

    /** Convenience builder for {@code curator.run}. */
    public static Entry curatorEntry(SkillCurator.CuratorRunReport report, String mode) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("transitions", report.transitions().toString());
        extra.put("duration_ms", report.durationMs());
        if (report.dryRunReportPath() != null) {
            extra.put("report_path", report.dryRunReportPath());
        }
        return new Entry(
                report.ranAt(),
                "curator",
                "curator.run",
                null,
                mode,
                null,
                null,
                null,
                null,
                extra);
    }
}
