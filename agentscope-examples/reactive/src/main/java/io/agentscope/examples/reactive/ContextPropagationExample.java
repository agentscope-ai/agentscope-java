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

import reactor.core.publisher.Mono;

/**
 * 演示 Project Reactor Context 写入位置对 Context 可见性的影响。
 *
 * <p>核心知识点：
 * <ul>
 *     <li>{@code contextWrite} 必须放在 <b>下游</b>（靠近 subscribe 处）才能被上游操作符读取</li>
 *     <li>Context 从 subscribe 向上传播，所以只有下游设置的 Context 才对上游可见</li>
 *     <li>多个 contextWrite 在同一链中，只有最靠近 subscribe 的那个生效</li>
 * </ul>
 */
public class ContextPropagationExample {

    public static void main(String[] args) {
        System.out.println("=== 示例1：基础 Context 传播 ===");
        example1BasicContext();

        System.out.println("\n=== 示例2：contextWrite 位置的影响（核心测试）===");
        example2ContextWriteOrder();

        System.out.println("\n=== 示例3：doFinally 与 Context 配合（实际应用场景）===");
        example3DoFinallyWithContext();
    }

    // ============================================================
    // 示例 1：基础 Context 传播
    //   演示 defer + deferContextual + contextWrite 的基本用法
    // ============================================================

    static void example1BasicContext() {
        Mono.defer(
                        () -> {
                            System.out.println("[defer] 执行延迟操作");
                            return Mono.deferContextual(
                                    ctx -> {
                                        String value = ctx.getOrDefault("key", "未找到");
                                        System.out.println("[deferContextual] 读取到 key=" + value);
                                        return Mono.just("result");
                                    });
                        })
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 key=value");
                            return ctx.put("key", "value");
                        })
                .subscribe(result -> System.out.println("[subscribe] 结果: " + result));
    }

    // ============================================================
    // 示例 2：contextWrite 位置的影响
    //   核心测试：contextWrite 放在链的不同位置，对上游可见性不同
    // ============================================================

    static void example2ContextWriteOrder() {
        // --- 测试A：contextWrite 在链的末尾（下游），能读到 ---
        System.out.println("--- 测试A：contextWrite 在链的末尾 ---");
        Mono.just("data")
                .map(
                        data -> {
                            System.out.println("[map] 输入: " + data);
                            return data;
                        })
                .flatMap(
                        data ->
                                Mono.deferContextual(
                                        ctx -> {
                                            String value = ctx.getOrDefault("key", "未找到");
                                            System.out.println(
                                                    "[flatMap deferContextual] 读取到 key=" + value);
                                            return Mono.just(data + " -> " + value);
                                        }))
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 key=valueA");
                            return ctx.put("key", "valueA");
                        })
                .subscribe(result -> System.out.println("[subscribe] 结果: " + result));

        // --- 测试B：contextWrite 在链的开头（上游），读不到 ---
        System.out.println("\n--- 测试B：contextWrite 在链的开头 ---");
        Mono.just("data")
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 key=valueB");
                            return ctx.put("key", "valueB");
                        })
                .map(
                        data -> {
                            System.out.println("[map] 输入: " + data);
                            return data;
                        })
                .flatMap(
                        data ->
                                Mono.deferContextual(
                                        ctx -> {
                                            String value = ctx.getOrDefault("key", "未找到");
                                            System.out.println(
                                                    "[flatMap deferContextual] 读取到 key=" + value);
                                            return Mono.just(data + " -> " + value);
                                        }))
                .subscribe(result -> System.out.println("[subscribe] 结果: " + result));

        // --- 测试C：多个 contextWrite 在不同位置 ---
        System.out.println("\n--- 测试C：多个 contextWrite 在不同位置 ---");
        Mono.just("data")
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 position=first");
                            return ctx.put("position", "first");
                        })
                .map(
                        data -> {
                            System.out.println("[map] 输入: " + data);
                            return data;
                        })
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 position=second");
                            return ctx.put("position", "second");
                        })
                .flatMap(
                        data ->
                                Mono.deferContextual(
                                        ctx -> {
                                            String value = ctx.getOrDefault("position", "未找到");
                                            System.out.println(
                                                    "[flatMap deferContextual] 读取到 position="
                                                            + value);
                                            return Mono.just(data + " -> " + value);
                                        }))
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 position=third");
                            return ctx.put("position", "third");
                        })
                .subscribe(result -> System.out.println("[subscribe] 结果: " + result));

        System.out.println("\n✅ 结论：");
        System.out.println("   - 测试A: ✅ 获取到 'valueA' - contextWrite 在 flatMap 之后（下游）");
        System.out.println("   - 测试B: ❌ 获取到 '未找到' - contextWrite 在 flatMap 之前（上游）");
        System.out.println("   - 测试C: ✅ 获取到 'third' - 只有最下游的 contextWrite 可见");
        System.out.println();
        System.out.println("   ⚠️  核心要点：位置很重要！");
        System.out.println("   - contextWrite 必须放在读取操作符的下游（代码下方）");
        System.out.println("   - Context 从 subscribe() 开始向上传播，所以下游的设置才能被看到");
        System.out.println("   - 多个 contextWrite：只有最靠近 subscribe() 的那个生效");
    }

    // ============================================================
    // 示例 3：doFinally 与 Context 配合
    //   演示在 doFinally 清理回调中通过 Context 获取上下文信息
    // ============================================================

    static void example3DoFinallyWithContext() {
        System.out.println("--- 场景：带 Context 的清理操作 ---");

        StringBuilder tracker = new StringBuilder("已初始化");

        Mono.deferContextual(
                        ctx -> {
                            String bus = ctx.getOrDefault("bus", "无BUS");
                            System.out.println("[deferContextual] 读取到 bus=" + bus);
                            tracker.append(" -> 处理中");
                            return Mono.just("data");
                        })
                .doFinally(
                        signalType -> {
                            tracker.append(" -> 已清理");
                            System.out.println("[doFinally] 清理状态: " + tracker);
                            System.out.println("[doFinally] 信号类型: " + signalType);
                        })
                .contextWrite(
                        ctx -> {
                            System.out.println("[contextWrite] 设置 bus=subagent-bus");
                            return ctx.put("bus", "subagent-bus");
                        })
                .subscribe(result -> System.out.println("[subscribe] 结果: " + result));
    }
}
