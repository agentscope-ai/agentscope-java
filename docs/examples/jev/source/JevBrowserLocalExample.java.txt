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

import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Outcome;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Receipt;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Snapshot;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Source;
import io.agentscope.extensions.judge.jev.browser.JevPlaywrightSession;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Explicit real Chromium opt-in. Scripted decisions by default; real backends require flags. */
public final class JevBrowserLocalExample {
    private JevBrowserLocalExample() {}

    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 4 && args.length != 5)
                || !args[0].equals("--browser"))
            throw new IllegalArgumentException(
                    "--browser PATH [--live-jev MODEL | --live-qwen MODEL ENDPOINT]");
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                JevBrowserExample::syntheticAnswers;
        if (args.length > 2) {
            if (args.length == 4 && args[2].equals("--live-jev"))
                caller =
                        JevClient.builder()
                                        .apiKey(System.getenv("TYPESAFE_API_KEY"))
                                        .model(args[3])
                                        .timeout(Duration.ofSeconds(20))
                                        .retryPolicy(new JevRetryPolicy(0, Duration.ofMillis(1)))
                                        .build()
                                ::systemOne;
            else if (args.length == 5 && args[2].equals("--live-qwen"))
                caller = JevTraceBenchmark.qwenBackend(args[3], URI.create(args[4]));
            else throw new IllegalArgumentException("explicit live backend required");
        }
        var backend = caller;
        var usage = new CopyOnWriteArrayList<Usage>();
        var models = new CopyOnWriteArrayList<String>();
        Function<SystemOneRequest, Mono<SystemOneResult>> measured =
                request ->
                        backend.apply(request)
                                .doOnNext(
                                        reply -> {
                                            models.add(reply.model());
                                            if (reply.usage() != null) usage.add(reply.usage());
                                        });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    if (!exchange.getRequestMethod().equals("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                        return;
                    }
                    String body =
                            exchange.getRequestURI().getPath().equals("/policy")
                                    ? "<h1>Warranty policy</h1><p>"
                                            + JevBrowserExample.ANSWER
                                            + "</p>"
                                    : "<h1>Help center</h1><a href='/policy'>Warranty policy</a>";
                    byte[] bytes =
                            ("<!doctype html><title>Local read-only fixture</title>" + body)
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var actions = new AtomicInteger();
        var closures = new AtomicInteger();
        try {
            var source =
                    new Source(
                            req -> {
                                var delegate =
                                        new JevPlaywrightSession(
                                                JevBrowserExample.scope(req.context()),
                                                Path.of(args[1]),
                                                base + "/",
                                                Set.of(base + "/", base + "/policy"),
                                                Duration.ofSeconds(10));
                                return new JevBrowserSession() {
                                    public Scope scope() {
                                        return delegate.scope();
                                    }

                                    public Mono<Snapshot> observe() {
                                        return delegate.observe();
                                    }

                                    public Mono<Receipt> execute(Permit p, Snapshot s, Action a) {
                                        return delegate.execute(p, s, a)
                                                .doOnNext(
                                                        r -> {
                                                            if (r.outcome() == Outcome.APPLIED)
                                                                actions.incrementAndGet();
                                                        });
                                    }

                                    public Mono<Void> close() {
                                        return delegate.close()
                                                .doOnSuccess(v -> closures.incrementAndGet());
                                    }
                                };
                            },
                            JevBrowserExample::verify);
            long started = System.nanoTime();
            var result = JevBrowserExample.run(source, measured, actions, closures);
            double elapsed = (System.nanoTime() - started) / 1e6;
            if (result.browserActions() != 1 || result.closedSessions() != 1)
                throw new IllegalStateException("real browser lifecycle failed: " + result);
            System.out.println(
                    "Real isolated browser and Agent; local read-only pages, scripted generation,"
                            + " judgment="
                            + (args.length == 2 ? "offline" : args[2]));
            System.out.println(result);
            System.out.println(
                    "elapsedMillis="
                            + elapsed
                            + ", actualModels="
                            + models.stream().distinct().toList()
                            + ", responsesWithUsage="
                            + usage.size()
                            + ", inputTokens="
                            + usage.stream().mapToLong(Usage::inputTokens).sum()
                            + ", outputTokens="
                            + usage.stream().mapToLong(Usage::outputTokens).sum());
        } finally {
            server.stop(0);
        }
    }
}
