/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.reactive;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 演示 SSE 流式对话断连后通过 sessionId 重连、回放历史事件的完整脉络。
 *
 * <h3>交互流程</h3>
 * <pre>
 * 启动 → 提示输入命令
 *   ├─ chat &lt;sessionId&gt; "消息" → 开始对话，mock LLM 持续产生 SSE 事件
 *   ├─ replay &lt;sessionId&gt;       → 断连重连，回放历史全部事件 + 接收后续实时事件
 *   ├─ cancel &lt;sessionId&gt;      → 取消指定 session 的 mock 流
 *   ├─ list                     → 列出所有活跃 session
 *   └─ quit                     → 退出
 * </pre>
 *
 * <h3>演示脉络</h3>
 * <ol>
 *   <li>基础流式: session-1 发起对话，逐条打印 mock SSE 事件 (take(4) 模拟中途断连)</li>
 *   <li>后台持续: mock 流在后台持续产生事件，数据积压在 sink 缓存中</li>
 *   <li>重连回放: 用同一个 sessionId 重连，先看到历史回放，再看到后续实时</li>
 *   <li>多 session 隔离: session-2 独立流，与 session-1 互不干扰</li>
 *   <li>取消 session: cancel 命令停止 mock 流，sink 仍可回放已缓存的历史</li>
 * </ol>
 */
public class SseReplayDemo {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws InterruptedException {
        SessionStreamManager manager = new SessionStreamManager();

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   SSE Stream Replay Demo                    ║");
        System.out.println("║   演示 SSE 流断连重连 + 历史事件回放          ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 命令:                                       ║");
        System.out.println("║   chat <sid> <msg>  — 开始 mock 对话         ║");
        System.out.println("║   replay <sid>      — 重连并回放历史         ║");
        System.out.println("║   cancel <sid>      — 取消 session 流        ║");
        System.out.println("║   list              — 列出活跃 session       ║");
        System.out.println("║   quit              — 退出                   ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equals("quit")) {
                System.out.println("退出。");
                break;
            }

            if (line.equals("list")) {
                manager.listSessions();
                continue;
            }

            if (line.startsWith("cancel ")) {
                String sessionId = line.substring(7).trim();
                manager.cancel(sessionId);
                continue;
            }

            if (line.startsWith("chat ")) {
                String rest = line.substring(5).trim();
                int spaceIdx = rest.indexOf(' ');
                if (spaceIdx == -1) {
                    System.out.println("[错误] 用法: chat <sessionId> \"消息\"");
                    continue;
                }
                String sessionId = rest.substring(0, spaceIdx);
                String message = rest.substring(spaceIdx + 1).trim();
                if (message.startsWith("\"") && message.endsWith("\"")) {
                    message = message.substring(1, message.length() - 1);
                }
                manager.chat(sessionId, message);
                continue;
            }

            if (line.startsWith("replay ")) {
                String sessionId = line.substring(7).trim();
                manager.replay(sessionId);
                continue;
            }

            System.out.println("[错误] 未知命令: " + line);
        }

        scanner.close();
        manager.shutdown();
    }

    // ============================================================
    // SessionStreamManager
    // ============================================================

    /** 管理 session → SSE sink 的映射，支持历史回放与实时订阅。 */
    static class SessionStreamManager {

        private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

        /** 列出所有活跃 session。 */
        void listSessions() {
            if (sessions.isEmpty()) {
                System.out.println("[list] 当前无活跃 session");
                return;
            }
            System.out.println("[list] 活跃 session:");
            for (SessionEntry entry : sessions.values()) {
                String status = entry.running ? "running" : "ended";
                String since = entry.createdAt.format(TS_FORMAT);
                System.out.printf(
                        "  - %s (%s, 创建于 %s, 已产生 %d 条事件)\n",
                        entry.sessionId, status, since, entry.eventSeq.get());
            }
        }

        /** 开始对话：创建 sink → 启动持续 mock Flux → 订阅并打印（take(4) 模拟断连）。 */
        void chat(String sessionId, String message) {
            SessionEntry entry = sessions.computeIfAbsent(sessionId, SessionEntry::new);

            System.out.printf(
                    "\n[SESSION %s] 开始对话: \"%s\" (持续产生随机事件，订阅仅消费前 4 条)\n", sessionId, message);
            System.out.flush();

            if (entry.running) {
                System.out.println("[提示] 该 session 已有流在运行");
            }

            entry.startMockStream();

            // 订阅：take(4) 模拟用户只消费 4 条就断开
            Disposable prev = entry.activeSub;
            if (prev != null) {
                prev.dispose();
            }
            entry.activeSub =
                    entry.sink
                            .asFlux()
                            .take(4)
                            .doOnNext(
                                    event -> {
                                        String ts = LocalDateTime.now().format(TS_FORMAT);
                                        System.out.printf("  [%s] %s\n", ts, event);
                                    })
                            .doOnComplete(
                                    () -> {
                                        System.out.println("\n[断开] 已取消订阅，已消费 4 条（后台仍在持续产生事件...）\n");
                                        System.out.flush();
                                    })
                            .doOnError(e -> System.err.println("[错误] " + e.getMessage()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
        }

        /** 重连回放：先回放历史全部事件，再接收后续实时事件。 */
        void replay(String sessionId) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry == null) {
                System.out.println("[错误] session " + sessionId + " 不存在，请先用 chat 创建");
                return;
            }

            System.out.printf("\n[SESSION %s] 重连——先回放历史，再接收实时\n", sessionId);
            System.out.flush();

            final int cached = entry.eventSeq.get();
            final AtomicInteger consumed = new AtomicInteger(0);
            final boolean[] replayDone = {cached == 0};

            if (cached == 0) {
                System.out.println("  --- 无历史事件，直接进入实时 ---");
            } else {
                System.out.printf("  --- 已缓存 %d 条历史事件，开始回放 ---\n", cached);
            }

            // 先取消旧订阅
            Disposable prev = entry.activeSub;
            if (prev != null) {
                prev.dispose();
            }

            entry.activeSub =
                    entry.sink
                            .asFlux()
                            .doOnNext(
                                    event -> {
                                        String ts = LocalDateTime.now().format(TS_FORMAT);
                                        if (!replayDone[0]) {
                                            System.out.printf("  [REPLAY] %s\n", event);
                                            if (consumed.incrementAndGet() >= cached) {
                                                replayDone[0] = true;
                                                System.out.println(
                                                        "  --- 以上是历史回放 ("
                                                                + cached
                                                                + " 条)，以下是实时 ---");
                                                System.out.flush();
                                            }
                                        } else {
                                            System.out.printf("  [LIVE]   %s\n", event);
                                        }
                                    })
                            .doOnComplete(
                                    () -> {
                                        System.out.printf("  [完成] %s 流已结束\n", sessionId);
                                        System.out.flush();
                                    })
                            .doOnError(e -> System.err.println("[错误] " + e.getMessage()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
        }

        /** 取消指定 session 的 mock 流，停止产生新事件，但保留已缓存的历史。 */
        void cancel(String sessionId) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry == null) {
                System.out.println("[错误] session " + sessionId + " 不存在");
                return;
            }
            if (!entry.running) {
                System.out.println("[提示] session " + sessionId + " 流已经停止");
                return;
            }

            if (entry.activeSub != null) {
                entry.activeSub.dispose();
                entry.activeSub = null;
            }
            if (entry.mockDisposable != null) {
                entry.mockDisposable.dispose();
                entry.mockDisposable = null;
            }
            entry.running = false;
            // 不调用 tryEmitComplete，保留 sink 以便回放之后还可继续 chat 追加
            System.out.printf(
                    "[取消] session %s 流已停止 (共产生 %d 条事件，缓存已保留)\n", sessionId, entry.eventSeq.get());
        }

        /** 关闭所有 session，清理资源。 */
        void shutdown() {
            for (SessionEntry entry : sessions.values()) {
                if (entry.activeSub != null) {
                    entry.activeSub.dispose();
                }
                if (entry.mockDisposable != null) {
                    entry.mockDisposable.dispose();
                }
                entry.sink.tryEmitComplete();
            }
            sessions.clear();
            System.out.println("[shutdown] 所有 session 已清理");
        }
    }

    // ============================================================
    // SessionEntry
    // ============================================================

    /** 单个 session 的状态。 */
    static class SessionEntry {
        final String sessionId;
        final LocalDateTime createdAt;
        final Sinks.Many<String> sink;
        final AtomicInteger eventSeq;
        volatile boolean running;
        volatile Disposable activeSub;
        volatile Disposable mockDisposable;

        private static final Random RANDOM = new Random();
        private static final String[] THOUGHTS = {
            "Hmm, let me think about this...",
            "I need to consider the edge cases here.",
            "Looking at the code structure...",
            "This seems like a race condition.",
            "Let me trace the execution flow.",
            "The error handling could be improved.",
            "I should check the database schema first.",
            "This pattern looks familiar...",
            "Maybe we can refactor this part.",
            "The performance bottleneck might be here."
        };
        private static final String[] TOOL_NAMES = {
            "read_file", "search_code", "run_test", "git_diff", "db_query"
        };
        private static final String[] TOOL_DETAILS = {
            "Scanning source tree...",
            "Executing search query...",
            "Running unit tests...",
            "Checking git history...",
            "Querying database..."
        };
        private static final String[] TEXT_CHUNKS = {
            "Based on my analysis, there are several issues to address.",
            "The main problem lies in the async callback chain.",
            "I found a potential null pointer at the entry point.",
            "Let me suggest a fix for this memory leak.",
            "The API response format needs to be standardized.",
            "We should add more validation here.",
            "This function could be split into smaller units.",
            "Consider using a connection pool for better performance.",
            "The caching strategy here is suboptimal.",
            "I recommend adding a circuit breaker pattern."
        };

        SessionEntry(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = LocalDateTime.now();
            this.sink = Sinks.many().replay().all();
            this.eventSeq = new AtomicInteger(0);
        }

        /** 启动持续 mock LLM 流。每隔 500~900ms 随机产生一条事件，无限持续直到被 cancel。 */
        void startMockStream() {
            if (running) {
                return;
            }
            running = true;

            mockDisposable =
                    Flux.interval(Duration.ofMillis(500 + RANDOM.nextInt(400)))
                            .map(tick -> generateRandomEvent())
                            .doOnNext(
                                    event -> {
                                        eventSeq.incrementAndGet();
                                        Sinks.EmitResult result = sink.tryEmitNext(event);
                                        if (result.isFailure()) {
                                            System.err.println("[warn] emit failed: " + result);
                                        }
                                    })
                            .doOnError(
                                    e -> {
                                        running = false;
                                        System.err.println("[warn] mock stream error: " + e);
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
        }

        /** 随机生成一条 mock SSE 事件。 */
        private String generateRandomEvent() {
            int type = RANDOM.nextInt(10);
            int seq = eventSeq.get() + 1;
            String ts = LocalDateTime.now().format(TS_FORMAT);

            if (type < 4) {
                // 40% think 事件
                String thought = THOUGHTS[RANDOM.nextInt(THOUGHTS.length)];
                return String.format(
                        "{\"t\":\"think\",\"seq\":%d,\"ts\":\"%s\",\"d\":\"%s\"}",
                        seq, ts, thought);
            } else if (type < 7) {
                // 30% tool 事件
                String name = TOOL_NAMES[RANDOM.nextInt(TOOL_NAMES.length)];
                String detail = TOOL_DETAILS[RANDOM.nextInt(TOOL_DETAILS.length)];
                return String.format(
                        "{\"t\":\"tool\",\"seq\":%d,\"ts\":\"%s\",\"n\":\"%s\",\"d\":\"%s\"}",
                        seq, ts, name, detail);
            } else {
                // 30% text 事件
                String text = TEXT_CHUNKS[RANDOM.nextInt(TEXT_CHUNKS.length)];
                return String.format(
                        "{\"t\":\"text\",\"seq\":%d,\"ts\":\"%s\",\"d\":\"%s\"}", seq, ts, text);
            }
        }
    }
}
