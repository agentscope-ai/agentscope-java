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

import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 演示 Reactor {@code then()} 操作符的语义，以及 ReActAgent 中
 * {@code doOnNext + then(buildFinal)} 模式下「逐个累积、最后产出」
 * 的执行时序。
 */
public class ThenOperatorExample {

    public static void main(String[] args) {
        basicThen();
        System.out.println("\n----------\n");
        thenWithPublisher();
        System.out.println("\n----------\n");
        accumulateThenBuild();
        System.out.println("\n----------\n");
        flatMapVsThen();
    }

    // ========== ① 基础 then() — 忽略所有元素 ==========

    static void basicThen() {
        System.out.println("=== ① 基础 then() — 忽略所有元素 ===");

        Flux<String> source = Flux.just("a", "b", "c");

        source.doOnNext(item -> System.out.println("  doOnNext: " + item))
                .then()
                .doOnSuccess(
                        v ->
                                System.out.println(
                                        "  then() 完成，返回: " + (v == null ? "Mono.empty()" : v)))
                .subscribe();
    }

    // ========== ② then(Mono) — 源完成后执行另一个 Mono ==========

    static void thenWithPublisher() {
        System.out.println("=== ② then(Mono) — 源完成后执行另一个 Mono ===");

        Flux<Integer> numbers = Flux.just(1, 2, 3);

        numbers.doOnNext(n -> System.out.println("  doOnNext: " + n))
                .then(
                        Mono.defer(
                                () -> {
                                    System.out.println("  👈 then() 内的 Mono 才开始执行（stream 已结束）");
                                    return Mono.just("最终结果");
                                }))
                .doOnSuccess(result -> System.out.println("  最终返回值: " + result))
                .subscribe();
    }

    // ========== ③ 模拟 ReActAgent 的「逐个累积 + 最后产出」模式 ==========

    static void accumulateThenBuild() {
        System.out.println("=== ③ 模拟 ReActAgent 的累积模式 ===");

        List<String> accumulator = new ArrayList<>();

        Flux<String> chunks = Flux.just("今天", "天气", "很好", "。");

        // 每个 chunk 到来时累积，但不产出最终结果
        chunks.doOnNext(
                        chunk -> {
                            accumulator.add(chunk);
                            System.out.println("  📥 收到 chunk: \"" + chunk + "\"");
                        })
                // stream 全部结束后，才 build 最终消息
                .then(
                        Mono.defer(
                                () -> {
                                    String finalMessage = String.join("", accumulator);
                                    System.out.println(
                                            "  🏗 buildFinalMessage: 拼接\""
                                                    + accumulator.size()
                                                    + "\"个 chunk");
                                    return Mono.just(finalMessage);
                                }))
                .doOnSuccess(result -> System.out.println("  最终 Msg: \"" + result + "\""))
                .subscribe();
    }

    // ========== ④ flatMap vs then — 每元素执行 vs 只执行一次 ==========

    static void flatMapVsThen() {
        System.out.println("=== ④ flatMap vs then — 执行次数对比 ===");

        // flatMap：每来一个元素就执行，返回 Flux —— 同维输入同维输出
        Flux.just(1, 2, 3)
                .doOnNext(n -> System.out.println("  flatMap 入: " + n))
                .flatMap(n -> Mono.just("  → flatMap 出: " + n)) // 每个 n 执行一次
                .subscribe(System.out::println);

        System.out.println();

        // then：忽略所有元素，等源完成后执行一次，返回 Mono —— 降维 N→1
        Flux.just(1, 2, 3)
                .doOnNext(n -> System.out.println("  then 忽略: " + n))
                .then(Mono.just("  → then 出: 只有一条 (所有元素被忽略)"))
                .subscribe(System.out::println);
    }
}
