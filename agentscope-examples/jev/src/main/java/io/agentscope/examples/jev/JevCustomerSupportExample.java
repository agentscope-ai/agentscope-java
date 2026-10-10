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

package io.agentscope.examples.jev;

import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.time.Duration;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Offline by default; --live explicitly enables one billable request. Never executes refunds. */
public final class JevCustomerSupportExample {
    private JevCustomerSupportExample() {}

    public static JevJudge.Definition definition() {
        return io.agentscope.extensions.judge.jev.application.JevCustomerSupport.definition();
    }

    public static void main(String[] args) {
        boolean live = args.length == 1 && "--live".equals(args[0]);
        if (args.length > 0 && !live)
            throw new IllegalArgumentException("Only --live is supported");
        JevJudge judge =
                live
                        ? new JevJudge(JevClient.builder().build(), Duration.ofSeconds(8))
                        : new JevJudge(
                                request ->
                                        Mono.just(
                                                new SystemOneResult(
                                                        "offline-fixture",
                                                        Map.of(
                                                                "covers_request",
                                                                new NoulAnswer(0.9),
                                                                "unsupported_commitment",
                                                                new NoulAnswer(0.95)),
                                                        null)),
                                Duration.ofSeconds(1));
        var state =
                Map.of(
                        "request",
                        "如果今天还不能发货，请帮我申请退款。",
                        "draft",
                        "您的退款已完成。",
                        "business_record",
                        "尚未发起退款，发货状态待查询。");
        var result = judge.judge(state, definition()).block();
        System.out.println(live ? "LIVE judgment" : "OFFLINE fixture, not measured model accuracy");
        System.out.println(result);
        System.out.println("Shadow only: retain draft for review; no business action executed.");
    }
}
