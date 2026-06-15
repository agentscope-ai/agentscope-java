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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that performs periodic memory maintenance after each agent call.
 *
 * <p>Replaces the background {@code MemoryMaintenanceScheduler} with a hook-driven,
 * event-loop-friendly approach. Fires on {@link PostCallEvent} (priority 6, after
 * {@link MemoryFlushHook} at priority 5) and is throttled by a configurable minimum gap
 * so it does not run on every single call.
 *
 * <p>Maintenance steps executed in order:
 * <ol>
 *   <li>Expire daily memory files older than {@code dailyFileRetentionDays} by moving
 *       them to {@code memory/archive/}.</li>
 *   <li>Run LLM-based consolidation ({@link MemoryConsolidator#consolidate}) if a
 *       consolidator is configured.</li>
 *   <li>Prune session log files older than {@code sessionRetentionDays}.</li>
 * </ol>
 *
 * <p>All file I/O goes through {@link AbstractFilesystem} (obtained from
 * {@link WorkspaceManager}), making this backend-agnostic across Local, Sandbox, and
 * Remote filesystems.
 */
public class MemoryMaintenanceHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceHook.class);

    /** Default minimum gap between two maintenance runs. */
    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    private final WorkspaceManager workspaceManager;
    private final MemoryConsolidator consolidator;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;
    private final Duration minGap;

    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>(Instant.EPOCH);

    private volatile RuntimeContext runtimeContext;

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    public MemoryMaintenanceHook(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
    }

    public MemoryMaintenanceHook(
            WorkspaceManager workspaceManager, MemoryConsolidator consolidator) {
        this(workspaceManager, consolidator, 90, 180, DEFAULT_MIN_GAP);
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (!(event instanceof PostCallEvent)) {
            return Mono.just(event);
        }
        // Instant 是 JDK 8+ 的时间戳类，表示 UTC 时间线上的一个纳秒精度时刻。
        // Instant.now() 获取当前 UTC 时刻，类似于 System.currentTimeMillis() 但精度更高。
        Instant now = Instant.now();

        // 读取上次维护执行的时间（初始值为 Instant.EPOCH = 1970-01-01T00:00:00Z）
        Instant last = lastRunAt.get();

        // 节流检查：距离上次执行不足 minGap（默认 30 分钟）则跳过
        // Duration.between(a, b) 返回两个时刻之间的时间差，compareTo 比较大小
        if (Duration.between(last, now).compareTo(minGap) < 0) {
            return Mono.just(event);
        }

        // CAS（Compare-And-Set）原子操作：仅当 lastRunAt 当前值仍为 last 时，才更新为 now。
        // 这是无锁并发控制——如果另一个线程先一步更新了 lastRunAt，CAS 失败返回 false，
        // 当前线程直接跳过，保证同时只有一个线程执行维护任务。
        if (!lastRunAt.compareAndSet(last, now)) {
            return Mono.just(event);
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        // Mono.fromRunnable 将同步的维护逻辑包装为 Mono，使其融入响应式链。
        // .onErrorResume 捕获 runMaintenance 中的所有异常，仅打日志不中断事件链。
        // .thenReturn(event) 无论维护成功或失败，最终都将原事件透传给下一个 Hook。
        return Mono.fromRunnable(() -> runMaintenance(rc))
                .onErrorResume(
                        e -> {
                            log.warn("Memory maintenance failed: {}", e.getMessage());
                            return Mono.empty();
                        })
                .thenReturn(event);
    }

    private void runMaintenance(RuntimeContext rc) {
        log.debug("Running memory maintenance...");
        expireDailyFiles(rc);
        consolidateMemory(rc);
        pruneOldSessions(rc);
        log.debug("Memory maintenance completed");
    }

    private void expireDailyFiles(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.md", WorkspaceConstants.MEMORY_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String fileName = fileName(fi.path());
            if (fileName.startsWith(".")) {
                continue;
            }
            String baseName =
                    fileName.endsWith(".md")
                            ? fileName.substring(0, fileName.length() - 3)
                            : fileName;
            try {
                LocalDate fileDate = LocalDate.parse(baseName);
                if (fileDate.isBefore(cutoff)) {
                    String fromPath = WorkspaceConstants.MEMORY_DIR + "/" + fileName;
                    String toPath = WorkspaceConstants.MEMORY_DIR + "/archive/" + fileName;
                    fs.move(rc, fromPath, toPath);
                    log.debug("Archived expired daily file: {}", fileName);
                }
            } catch (Exception e) {
                // not a date-named file, skip
            }
        }
    }

    private void consolidateMemory(RuntimeContext rc) {
        if (consolidator == null) {
            return;
        }
        try {
            consolidator.consolidate(rc).block();
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }
    }

    /**
     * 清理过期的 {@code *.log.jsonl} 会话日志文件。
     *
     * <h3>什么是 .log.jsonl？</h3>
     * 双文件模式下，每次 agent 对话产生一对文件：
     * <pre>
     * agents/{agentId}/sessions/
     * ├── {sessionId}.jsonl          ← LLM 上下文（会被 CompactionHook 截断/压缩重写）
     * └── {sessionId}.log.jsonl      ← 完整历史日志（只追加，永不压缩，用于审计+搜索）
     * </pre>
     * {@code .jsonl} 是"工作记忆"（可丢弃重写），{@code .log.jsonl} 是"档案库"
     * （永久保留直到过期清理）。两者由 {@code SessionTree.flush()} 同时写入。
     *
     * <h3>写入链路</h3>
     * <pre>
     * Agent.call() 结束 → MemoryFlushHook → MemoryFlushManager.offloadMessages()
     * → SessionTree.append() → SessionTree.flush() → 同时写 .jsonl 和 .log.jsonl
     * </pre>
     *
     * <h3>清理流程</h3>
     * <pre>
     *   1. 通过 filesystem.glob 匹配 agents/*.log.jsonl
     *   2. 计算截止时间 cutoff = now - sessionRetentionDays 天
     *   3. 遍历匹配到的文件：
     *      - 跳过目录
     *      - 跳过无 modifiedAt 的文件（保守策略，不删）
     *      - 解析 modifiedAt → 如果早于 cutoff → 删除
     *      - 解析或删除失败只打日志，不抛异常
     *
     * 举例（sessionRetentionDays=180，今天是 2026-06-06）：
     *   agents/
     *   ├── sess-abc.log.jsonl  modifiedAt=2026-06-01  → 保留（距今 5 天）
     *   ├── sess-xyz.log.jsonl  modifiedAt=2025-09-01  → 删除（距今 278 天 > 180）
     *   └── sess-old.log.jsonl  modifiedAt=null        → 保留（无法判断，保守跳过）
     * </pre>
     */
    private void pruneOldSessions(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.log.jsonl", WorkspaceConstants.AGENTS_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(sessionRetentionDays));
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String modifiedAt = fi.modifiedAt();
            if (modifiedAt == null || modifiedAt.isEmpty()) {
                continue;
            }
            try {
                Instant modified = Instant.parse(modifiedAt);
                if (modified.isBefore(cutoff)) {
                    fs.delete(rc, fi.path());
                    log.debug("Pruned old session file: {}", fi.path());
                }
            } catch (Exception e) {
                log.warn("Failed to check/prune {}: {}", fi.path(), e.getMessage());
            }
        }
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
