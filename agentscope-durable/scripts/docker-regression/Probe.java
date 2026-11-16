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

import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.durable.*;
import io.agentscope.durable.DurableTypes.*;
import io.agentscope.extensions.durable.jdbc.*;
import io.agentscope.extensions.durable.rocketmq.*;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/** Independent processes sharing only PostgreSQL, RocketMQ and the counting model endpoint. */
public class Probe {
    static final SessionKey KEY = new SessionKey("regression", "session");

    static PGSimpleDataSource database() {
        var source = new PGSimpleDataSource();
        source.setURL(
                "jdbc:postgresql://pg/postgres?user=postgres&connectTimeout=4&socketTimeout=10");
        return source;
    }

    static class Tool implements Executor {
        public Capabilities capabilities() {
            boolean recoverable = System.getenv("OPAQUE") == null;
            return new Capabilities(recoverable, recoverable, false);
        }

        public ExecutionResult startExecution(ExecutionRequest request) {
            try (var conn = database().getConnection()) {
                conn.setAutoCommit(false);
                for (String sql :
                        List.of(
                                "insert into starts values (?)",
                                "insert into effects values (?) on conflict do nothing")) {
                    try (var insert = conn.prepareStatement(sql)) {
                        insert.setString(1, request.executionId());
                        insert.executeUpdate();
                    }
                }
                conn.commit();
                System.out.println("EFFECT " + request.turnId());
                while (Files.exists(Path.of("/tmp/hold-effect"))) Thread.sleep(50);
                return query(request, null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public ExecutionResult query(ExecutionRequest request, String handle) {
            try (var conn = database().getConnection();
                    var select = conn.prepareStatement("select 1 from effects where id=?")) {
                select.setString(1, request.executionId());
                boolean committed = select.executeQuery().next();
                return new ExecutionResult(
                        committed ? ExecutionState.SUCCEEDED : ExecutionState.NOT_FOUND,
                        committed ? request.executionId() : null,
                        committed ? DurableJson.value("echo: " + request.call().arguments()) : null,
                        null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public ExecutionResult cancel(ExecutionRequest request, String handle) {
            return query(request, handle);
        }
    }

    public static String answer(String turn, GenerateOptions options) {
        System.out.println("MODEL_REQUEST " + turn);
        try {
            var req =
                    HttpRequest.newBuilder(URI.create("http://model:8080/" + turn))
                            .POST(HttpRequest.BodyPublishers.noBody());
            options.getAdditionalHeaders().forEach(req::header);
            var response =
                    HttpClient.newHttpClient()
                            .send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("model failed");
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void model() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(8080), 0);
        var statusByKey = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        var turn = exchange.getRequestURI().getPath().substring(1);
                        var key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                        if (key == null) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        int status = statusByKey.computeIfAbsent(key, ignored -> {
                            System.out.println("INFERENCE " + turn);
                            return Files.exists(Path.of("/tmp/fail")) ? 500 : 200;
                        });
                        System.out.println("RECEIVED " + turn);
                        while (Files.exists(Path.of("/tmp/block"))) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (status != 200) {
                            exchange.sendResponseHeaders(status, -1);
                            return;
                        }
                        var bytes =
                                EchoBrainExample.FINAL_ANSWER.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("model")) {
            model();
            return;
        }
        if (args[0].equals("init")) {
            PostgresBackend.initializeSchema(database());
            return;
        }
        if (args[0].equals("verify-effect")) {
            var request = DurableJson.convert(DurableJson.parse(args[1]), ExecutionRequest.class);
            var verified = new Tool().query(request, null);
            if (verified.state() != ExecutionState.SUCCEEDED)
                throw new AssertionError("missing external evidence");
            var command =
                    new ExecutionReconciliation(
                            "verify-" + request.executionId(),
                            verified,
                            "postgres:effects/" + request.executionId());
            try (var records =
                    new JdbcRecordStore(
                            () -> database().getConnection(),
                            JdbcRecordStore.Dialect.POSTGRESQL,
                            false)) {
                var ledger =
                        new io.agentscope.durable.worker.TransactionalExecutionStore(
                                records, "hands");
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while (System.nanoTime() < deadline) {
                    var lease = ledger.claim(request, "verifier", Duration.ofSeconds(5));
                    if (lease.isPresent()) {
                        ledger.reconcile(lease.get(), command);
                        ledger.reconcile(lease.get(), command);
                        if (!ledger.reconciliation(request, command.requestId())
                                .orElseThrow()
                                .equals(command))
                            throw new AssertionError("missing reconciliation receipt");
                        return;
                    }
                    Thread.sleep(100);
                }
                throw new AssertionError("verification lease deadline");
            }
        }
        var mq = ClientConfiguration.newBuilder().setEndpoints("broker:8081").build();
        if (args[0].equals("hands")) {
            try (var records =
                            new JdbcRecordStore(
                                    () -> database().getConnection(),
                                    JdbcRecordStore.Dialect.POSTGRESQL,
                                    false);
                    var worker =
                            RocketMqWorker.builder()
                                    .namespace("regression")
                                    .executor("hands", new Tool())
                                    .recordStore(records)
                                    .clientConfiguration(mq)
                                    .authorize(command -> {})
                                    .leaseDuration(Duration.ofSeconds(5))
                                    .build()) {
                worker.start();
                System.out.println("READY");
                Thread.sleep(Long.MAX_VALUE);
            }
            return;
        }
        try (var backend = PostgresBackend.builder().dataSource(database()).build()) {
            if (args[0].equals("brain")) {
                var executor =
                        RocketMqRemoteExecutor.builder()
                                .namespace("regression")
                                .clientConfiguration(mq)
                                .build();
                try (var runtime = EchoBrainExample.runtime(backend, executor)) {
                    runtime.createSession(KEY, EchoBrainExample.HARNESS);
                    runtime.start();
                    System.out.println("READY");
                    Thread.sleep(Long.MAX_VALUE);
                }
            } else
                try (var runtime = DurableRuntime.builder().backend(backend).build()) {
                    var session = runtime.getSession(KEY);
                    if (args[0].equals("prune"))
                        backend.pruneEvents(KEY, session.snapshot().cursor());
                    else if (args[0].equals("cancel"))
                        session.getTurn(args[1]).cancel("cancel-" + args[1]);
                    else
                        System.out.println("TURN " + session.submit(args[1], "Call echo").turnId());
                }
        }
    }
}
