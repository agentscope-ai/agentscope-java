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
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 演示 {@link Mono#just}、{@link Mono#defer} 和 {@link Mono#using} 的功能特性。
 *
 * <p>核心对比：
 * <ul>
 *   <li>{@code Mono.just}  —— 组装时即求值，每次订阅拿到的是同一个结果</li>
 *   <li>{@code Mono.defer} —— 每次订阅时才求值，每次拿到的是新结果</li>
 *   <li>{@code Mono.using} —— 资源生命周期管理，保证无论成功/失败/取消，清理回调都会执行</li>
 * </ul>
 */
public class MonoCreationExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 示例 1：Mono.just vs Mono.defer —— 求值时机 ===");
        example1JustVsDefer();

        System.out.println("\n=== 示例 2：Mono.using —— 正常完成时自动清理 ===");
        example2UsingNormalComplete();

        System.out.println("\n=== 示例 3：Mono.using —— 出错时也会清理 ===");
        example3UsingOnError();

        System.out.println("\n=== 示例 4：Mono.using —— 取消订阅也会清理 ===");
        example4UsingOnCancel();

        System.out.println("\n=== 示例 5：Mono.using —— eagerCleanup 参数的影响 ===");
        example5UsingEagerCleanup();

        System.out.println("\n=== 示例 6：实战模拟 —— Agent 中的数据库连接 ===");
        example6RealWorldDatabaseConnection();
    }

    // ============================================================
    // 示例 1：Mono.just vs Mono.defer
    //   just 在声明时就执行 System.currentTimeMillis()
    //   defer 在每次 subscribe 时才执行
    // ============================================================

    static void example1JustVsDefer() {
        // Mono.just: 组装时立即求值
        System.out.println("--- Mono.just: 组装时求值 ---");
        System.out.println("[声明前] 当前时间戳(纳秒): " + System.nanoTime());
        Mono<Long> justMono = Mono.just(System.nanoTime());
        System.out.println("[声明后] 尚未订阅，但 just 已经拿取了时间戳");
        System.out.println("[1st subscribe] " + justMono.block());
        System.out.println("[2nd subscribe] " + justMono.block() + "  ← 和第一次相同！");

        // Mono.defer: 每次订阅时才求值
        System.out.println("\n--- Mono.defer: 订阅时求值 ---");
        Mono<Long> deferMono = Mono.defer(() -> Mono.just(System.nanoTime()));
        System.out.println("[声明后] 尚未订阅，defer 里的 lambda 还没执行");
        System.out.println("[1st subscribe] " + deferMono.block());
        sleep(1);
        System.out.println("[2nd subscribe] " + deferMono.block() + "  ← 和第一次不同！");
    }

    // ============================================================
    // 示例 2：Mono.using 正常完成
    //   资源在 use 完成后自动清理
    // ============================================================

    static void example2UsingNormalComplete() {
        AtomicBoolean cleaned = new AtomicBoolean(false);

        Mono<String> result =
                Mono.using(
                        // 1. 获取资源
                        () -> {
                            System.out.println("[acquire] 创建资源对象");
                            return new SimulatedResource("conn-001");
                        },
                        // 2. 使用资源
                        resource -> {
                            System.out.println("[use] 使用资源: " + resource.getId());
                            return Mono.just("查询结果: 42");
                        },
                        // 3. 清理资源（保证执行）
                        resource -> {
                            System.out.println("[cleanup] 释放资源: " + resource.getId());
                            resource.close();
                            cleaned.set(true);
                        });

        System.out.println("[链已组装] 尚未触发任何操作");
        String value = result.block();
        System.out.println("[block 返回] " + value);
        System.out.println("[验证] 清理回调已执行: " + cleaned.get());
    }

    // ============================================================
    // 示例 3：Mono.using 执行中出错
    //   即使 use 阶段抛异常，cleanup 仍会被调用
    // ============================================================

    static void example3UsingOnError() {
        AtomicBoolean cleaned = new AtomicBoolean(false);

        Mono<String> result =
                Mono.using(
                        () -> {
                            System.out.println("[acquire] 创建资源对象");
                            return new SimulatedResource("conn-002");
                        },
                        resource -> {
                            System.out.println("[use] 使用资源，即将抛出异常...");
                            throw new RuntimeException("查询执行失败！");
                        },
                        resource -> {
                            System.out.println("[cleanup] ⚠️ 虽然出错了，但清理照常执行: " + resource.getId());
                            resource.close();
                            cleaned.set(true);
                        });

        try {
            result.block();
        } catch (Exception e) {
            System.out.println("[捕获] 异常信息: " + e.getMessage());
        }
        System.out.println("[验证] 出错后清理回调仍执行: " + cleaned.get());
    }

    // ============================================================
    // 示例 4：Mono.using 取消订阅
    //   Disposable.dispose() 触发取消，cleanup 同样会被调用
    // ============================================================

    static void example4UsingOnCancel() throws InterruptedException {
        AtomicBoolean cleaned = new AtomicBoolean(false);

        Mono<String> result =
                Mono.using(
                        () -> {
                            System.out.println("[acquire] 创建资源对象");
                            return new SimulatedResource("conn-003");
                        },
                        resource -> {
                            System.out.println("[use] 开始一个耗时操作...");
                            return Mono.delay(Duration.ofSeconds(5)).map(tick -> "慢查询结果");
                        },
                        resource -> {
                            System.out.println("[cleanup] ⚠️ 取消订阅也会触发清理: " + resource.getId());
                            resource.close();
                            cleaned.set(true);
                        });

        System.out.println("[subscribe] 开始订阅");
        Disposable disposable =
                result.subscribe(
                        v -> System.out.println("[onNext] " + v),
                        e -> System.out.println("[onError] " + e.getMessage()),
                        () -> System.out.println("[onComplete] 完成"));

        sleep(500);
        System.out.println("[dispose] 主动取消订阅（不等到 5 秒结束）");
        disposable.dispose();
        sleep(500);

        System.out.println("[验证] 取消后清理回调仍执行: " + cleaned.get());
    }

    // ============================================================
    // 示例 5：Mono.using eagerCleanup 参数
    //   eagerCleanup=true:  上游 cancel 时立即清理（默认）
    //   eagerCleanup=false: 等下游 cancel 信号到达再清理
    // ============================================================

    static void example5UsingEagerCleanup() {
        System.out.println("--- eagerCleanup=true (默认): 上游取消时立即清理 ---");
        AtomicBoolean cleanedEager = new AtomicBoolean(false);
        Mono<String> eager =
                Mono.using(
                        () -> new SimulatedResource("conn-eager"),
                        r -> Mono.just("ok"),
                        r -> {
                            System.out.println("  [cleanup eager] 立即释放");
                            cleanedEager.set(true);
                        },
                        true // eagerCleanup
                        );
        eager.block();
        System.out.println("  [验证] 已清理: " + cleanedEager.get());

        System.out.println("\n--- eagerCleanup=false: 等下游确认再清理 ---");
        AtomicBoolean cleanedNonEager = new AtomicBoolean(false);
        Mono<String> nonEager =
                Mono.using(
                        () -> new SimulatedResource("conn-non-eager"),
                        r -> Mono.just("ok"),
                        r -> {
                            System.out.println("  [cleanup non-eager] 下游确认后释放");
                            cleanedNonEager.set(true);
                        },
                        false // non-eager cleanup
                        );
        nonEager.block();
        System.out.println("  [验证] 已清理: " + cleanedNonEager.get());
    }

    // ============================================================
    // 示例 6：实战模拟 —— Agent 中获取数据库连接执行查询
    //   模拟 AgentScope 中 Model 调用 API 的资源管理模式
    // ============================================================

    static void example6RealWorldDatabaseConnection() {
        System.out.println("模拟: Agent 获取 DB 连接 → 执行查询 → 释放连接");

        // 模拟连接池
        DatabasePool pool = new DatabasePool();

        // 使用 Mono.using 保证连接一定会归还
        Mono<String> queryResult =
                Mono.using(
                        pool::acquireConnection, // 1. 从连接池获取
                        conn -> {
                            // 2. 执行 SQL 查询
                            System.out.println("  [查询中] 使用连接 " + conn.getId());
                            return conn.executeQuery("SELECT name FROM users WHERE id = 1");
                        },
                        conn -> {
                            // 3. 无论成功/失败，归还连接
                            System.out.println("  [归还] 释放连接 " + conn.getId());
                            pool.releaseConnection(conn);
                        });

        // 正常执行
        System.out.println("[场景A] 查询成功:");
        String result = queryResult.block();
        System.out.println("  结果: " + result);
        System.out.println("  连接池使用次数: " + pool.getUsageCount());

        // 模拟出错
        System.out.println("\n[场景B] 查询失败（表不存在）:");
        Mono<String> faultyQuery =
                Mono.using(
                        pool::acquireConnection,
                        conn -> conn.executeQuery("SELECT * FROM non_existent_table"),
                        conn -> {
                            System.out.println("  [归还] 即使出错，也释放连接 " + conn.getId());
                            pool.releaseConnection(conn);
                        });

        try {
            faultyQuery.block();
        } catch (Exception e) {
            System.out.println("  异常: " + e.getMessage());
        }
        System.out.println("  连接池使用次数: " + pool.getUsageCount());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // 辅助类
    // ============================================================

    /** 模拟一个需要清理的资源（如文件句柄、数据库连接、HTTP 客户端等）。 */
    static class SimulatedResource {
        private final String id;
        private boolean closed = false;

        SimulatedResource(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }

        void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    /** 模拟数据库连接池。 */
    static class DatabasePool {
        private int usageCount = 0;
        private int connectionCounter = 0;

        DBConnection acquireConnection() {
            connectionCounter++;
            System.out.println("  [池] 获取连接 conn-" + connectionCounter);
            return new DBConnection("conn-" + connectionCounter, this);
        }

        void releaseConnection(DBConnection conn) {
            usageCount++;
            System.out.println("  [池] 连接 " + conn.getId() + " 已归还池中");
        }

        int getUsageCount() {
            return usageCount;
        }
    }

    /** 模拟数据库连接。 */
    static class DBConnection {
        private final String id;
        private final DatabasePool pool;

        DBConnection(String id, DatabasePool pool) {
            this.id = id;
            this.pool = pool;
        }

        String getId() {
            return id;
        }

        Mono<String> executeQuery(String sql) {
            if (sql.contains("non_existent")) {
                return Mono.error(new RuntimeException("表 'non_existent_table' 不存在"));
            }
            return Mono.just("[结果] 查询 '" + sql + "' 返回: Alice, Bob");
        }
    }
}
