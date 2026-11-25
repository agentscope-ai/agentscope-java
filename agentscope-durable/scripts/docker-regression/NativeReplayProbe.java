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
package io.agentscope.durable.examples.brainhands.echo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import io.agentscope.durable.*;
import io.agentscope.durable.DurableTypes.*;
import io.agentscope.durable.agentscope.*;
import io.agentscope.extensions.durable.jdbc.PostgresBackend;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/** Two real Brain processes. PostgreSQL counts starts independently of the native journal. */
public class NativeReplayProbe {
    static Model model(String scenario) {
        return new Model() {
            public String getModelName() {
                return "native-recovery-fixture";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                boolean check =
                        tools != null
                                && tools.stream()
                                        .anyMatch(t -> t.getName().equals("verify_outcome"));
                if (!check) System.out.println("REASONING_REQUEST");
                boolean applied =
                        messages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
                ContentBlock output;
                if (check) {
                    System.out.println("MODEL_CHECK");
                    output =
                            ToolUseBlock.builder()
                                    .id("lookup")
                                    .name("verify_outcome")
                                    .input(
                                            Map.of(
                                                    "hint",
                                                    scenario.equals("unknown")
                                                            ? "missing"
                                                            : "receipt-7"))
                                    .build();
                } else if (applied) {
                    if (scenario.endsWith("-direct"))
                        throw new AssertionError("Unexpected model request after direct tool");
                    output = TextBlock.builder().text("verified answer").build();
                } else {
                    output =
                            ToolUseBlock.builder()
                                    .id("original-call")
                                    .name("operation")
                                    .input(Map.of("receipt", "receipt-7"))
                                    .build();
                }
                return Flux.just(ChatResponse.builder().content(List.of(output)).build());
            }
        };
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        boolean old = args[1].equals("first");
        var key = new SessionKey("native-regression", scenario);
        var model = model(scenario);
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerAgentTool(
                                    new ToolBase(
                                            ToolBase.builder()
                                                    .name("operation")
                                                    .returnDirect(
                                                            old && scenario.endsWith("-direct"))
                                                    .description("Instrumented local operation")
                                                    .inputSchema(
                                                            Map.of(
                                                                    "type",
                                                                    "object",
                                                                    "properties",
                                                                    Map.of(
                                                                            "receipt",
                                                                            Map.of(
                                                                                    "type",
                                                                                    "string"))))) {
                                        public Mono<ToolResultBlock> callAsync(
                                                ToolCallParam param) {
                                            return Mono.fromCallable(
                                                    () -> {
                                                        try (var c =
                                                                        Probe.database()
                                                                                .getConnection();
                                                                var insert =
                                                                        c.prepareStatement(
                                                                                "insert into"
                                                                                    + " native_starts"
                                                                                    + " values"
                                                                                    + " (?,?,?,?)")) {
                                                            insert.setString(1, scenario);
                                                            insert.setString(
                                                                    2,
                                                                    binding.context().getTurnId());
                                                            insert.setString(
                                                                    3,
                                                                    param.getToolUseBlock()
                                                                            .getId());
                                                            insert.setString(
                                                                    4,
                                                                    (String)
                                                                            param.getInput()
                                                                                    .get(
                                                                                            "receipt"));
                                                            insert.executeUpdate();
                                                        }
                                                        if (old) Thread.sleep(Long.MAX_VALUE);
                                                        return ToolResultBlock.text("receipt-7");
                                                    });
                                        }
                                    });
                            return ReActAgent.builder()
                                    .name("native")
                                    .model(model)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of());
        boolean safe = scenario.startsWith("safe");
        // Intentionally reverse the registration on takeover. The dispatch fact must win.
        var policies =
                Map.of(
                        "operation",
                        old == safe
                                ? NativeToolRecovery.Replay.SAFE
                                : NativeToolRecovery.Replay.NEVER);
        var check =
                new NativeToolRecovery.ModelCheck(
                        model,
                        (dispatch, hint) -> {
                            try (var c = Probe.database().getConnection();
                                    var q =
                                            c.prepareStatement(
                                                    "select receipt from native_starts where"
                                                        + " scenario=? and turn_id=? and call_id=?"
                                                        + " and receipt=?")) {
                                q.setString(1, dispatch.thread().session().sessionId());
                                q.setString(2, dispatch.turnId());
                                q.setString(3, dispatch.call().getId());
                                q.setString(4, hint);
                                try (var row = q.executeQuery()) {
                                    if (!row.next()) return Optional.empty();
                                    return Optional.of(
                                            new NativeToolRecovery.Evidence(
                                                    "postgres:receipt/" + row.getString(1),
                                                    ToolResultBlock.success(row.getString(1))
                                                            .withIdAndName(
                                                                    dispatch.call().getId(),
                                                                    "operation")));
                                }
                            } catch (java.sql.SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        });
        try (var backend = PostgresBackend.builder().dataSource(Probe.database()).build();
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness(
                                        "native",
                                        "1",
                                        adapter.withNativeToolRecovery(policies, check))
                                .leaseDuration(Duration.ofSeconds(4))
                                .pollInterval(Duration.ofMillis(100))
                                .build()) {
            var turn = runtime.createSession(key, "native").submit("one", "perform operation");
            if (args[1].equals("operator")) {
                var thread = new ThreadKey(key, "main");
                var lease = backend.claim(thread, "operator", Duration.ofSeconds(4));
                while (lease.isEmpty()) {
                    Thread.sleep(100);
                    lease = backend.claim(thread, "operator", Duration.ofSeconds(4));
                }
                var context =
                        new HarnessContext(
                                backend,
                                lease.get(),
                                new java.util.concurrent.atomic.AtomicBoolean());
                try (var c = Probe.database().getConnection();
                        var q =
                                c.prepareStatement(
                                        "select call_id, receipt from native_starts where"
                                            + " scenario=? and turn_id=?")) {
                    q.setString(1, scenario);
                    q.setString(2, turn.turnId());
                    try (var row = q.executeQuery()) {
                        if (!row.next()) throw new AssertionError("Missing independent receipt");
                        var id = row.getString(1);
                        adapter.reconcileNativeTools(
                                context,
                                "repair",
                                "postgres:" + id,
                                Map.of(
                                        id,
                                        ToolResultBlock.success(row.getString(2))
                                                .withIdAndName(id, "operation")));
                    }
                }
                if (scenario.startsWith("cancel"))
                    backend.cancel(thread, turn.turnId(), "cancel-after-repair");
                System.out.println("REPAIRED");
                System.out.flush();
                Runtime.getRuntime().halt(0);
            }
            runtime.start();
            long end = System.nanoTime() + Duration.ofSeconds(80).toNanos();
            while (System.nanoTime() < end) {
                var v = turn.status();
                if (!old
                        && scenario.equals("unknown")
                        && v.status() == TurnStatus.WAITING
                        && WaitCondition.NATIVE_RECONCILIATION.equals(v.waiting())) {
                    System.out.println("PARKED " + turn.turnId());
                    return;
                }
                if (v.status().terminal()) {
                    if (scenario.startsWith("cancel") && v.status() == TurnStatus.CANCELLED) {
                        System.out.println("CANCELLED " + turn.turnId());
                        return;
                    }
                    if (v.status() != TurnStatus.SUCCEEDED || scenario.equals("unknown"))
                        throw new AssertionError(v);
                    System.out.println("SUCCEEDED " + turn.turnId());
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError(turn.status());
        }
    }
}
