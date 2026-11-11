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

package io.agentscope.extensions.judge.jev.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Link;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Operation;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Outcome;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Receipt;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Isolated Chromium adapter for a host-owned allowlist of known read-only pages.
 * GET filtering alone does not establish that an arbitrary website is read-only.
 * All Playwright methods, including cleanup, run on the same dedicated worker. */
public final class JevPlaywrightSession implements JevBrowserSession {
    private static final String SCRIPT = script();
    private final Scope scope;
    private final Path executable;
    private final String initialUrl;
    private final Set<String> allowed;
    private final Duration timeout;
    private final Scheduler worker = Schedulers.newSingle("jev-browser", true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Sinks.One<Boolean> closing = Sinks.one();
    private final Mono<Void> cleanup;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private JSHandle controller;
    private final Map<String, String> markers = new LinkedHashMap<>();

    public JevPlaywrightSession(
            Scope scope,
            Path executable,
            String initialUrl,
            Set<String> allowedUrls,
            Duration timeout) {
        this.scope = Objects.requireNonNull(scope);
        this.executable = Objects.requireNonNull(executable);
        this.initialUrl = Objects.requireNonNull(initialUrl);
        allowed = Set.copyOf(allowedUrls);
        this.timeout = Objects.requireNonNull(timeout);
        if (!allowed.contains(initialUrl)
                || allowed.isEmpty()
                || allowed.size() > 256
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("invalid browser host configuration");
        for (String url : allowed) {
            var uri = java.net.URI.create(url);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null)
                throw new IllegalArgumentException("authorized HTTP URLs required");
        }
        cleanup =
                Mono.<Void>fromRunnable(
                                () -> {
                                    try {
                                        if (browser != null) browser.close();
                                    } finally {
                                        if (playwright != null) playwright.close();
                                    }
                                })
                        .subscribeOn(worker)
                        .doFinally(s -> worker.dispose())
                        .cache();
    }

    @Override
    public Scope scope() {
        return scope;
    }

    private <T> Mono<T> task(Callable<T> call) {
        return Mono.defer(
                () -> {
                    if (closed.get())
                        return Mono.error(new IllegalStateException("BROWSER_CLOSED"));
                    return Mono.firstWithSignal(
                            Mono.fromCallable(
                                            () -> {
                                                if (closed.get())
                                                    throw new IllegalStateException(
                                                            "BROWSER_CLOSED");
                                                initialize();
                                                if (closed.get())
                                                    throw new IllegalStateException(
                                                            "BROWSER_CLOSED");
                                                return call.call();
                                            })
                                    .subscribeOn(worker),
                            closing.asMono()
                                    .flatMap(
                                            ignored ->
                                                    Mono.<T>error(
                                                            new IllegalStateException(
                                                                    "BROWSER_CLOSED"))));
                });
    }

    private void initialize() {
        if (page != null) return;
        playwright =
                Playwright.create(
                        new Playwright.CreateOptions()
                                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser =
                playwright
                        .chromium()
                        .launch(
                                new BrowserType.LaunchOptions()
                                        .setExecutablePath(executable)
                                        .setHeadless(true)
                                        .setTimeout((double) timeout.toMillis()));
        var context =
                browser.newContext(
                        new Browser.NewContextOptions()
                                .setAcceptDownloads(false)
                                .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                                .setViewportSize(1000, 700));
        context.route(
                "**/*",
                route -> {
                    String url = route.request().url().split("#", 2)[0];
                    if (!closed.get()
                            && route.request().method().equals("GET")
                            && allowed.contains(url)) route.resume();
                    else route.abort();
                });
        page = context.newPage();
        page.setDefaultTimeout(timeout.toMillis());
        page.setDefaultNavigationTimeout(timeout.toMillis());
        page.onDialog(Dialog::dismiss);
        page.navigate(initialUrl);
    }

    @Override
    public Mono<Snapshot> observe() {
        return task(
                () -> {
                    // Pump the browser event loop for navigation/scroll rendering before taking one
                    // atomic snapshot.
                    page.waitForTimeout(80);
                    String url = page.url().split("#", 2)[0];
                    if (!allowed.contains(url))
                        throw new IllegalStateException("UNAUTHORIZED_PAGE");
                    Map<?, ?> raw;
                    try {
                        if (controller == null) throw new IllegalStateException();
                        raw = (Map<?, ?>) controller.evaluate("c => c.snapshot()");
                    } catch (RuntimeException detached) {
                        if (controller != null)
                            try {
                                controller.dispose();
                            } catch (RuntimeException ignored) {
                            }
                        controller = page.evaluateHandle(SCRIPT, List.copyOf(allowed));
                        raw = (Map<?, ?>) controller.evaluate("c => c.snapshot()");
                    }
                    String marker = (String) raw.get("marker");
                    String version = JevBrowserSession.digest(marker);
                    markers.put(version, marker);
                    while (markers.size() > 64) markers.remove(markers.keySet().iterator().next());
                    var links = new ArrayList<Link>();
                    for (Object l : (List<?>) raw.get("links")) {
                        var x = (Map<?, ?>) l;
                        links.add(
                                new Link(
                                        (String) x.get("id"),
                                        (String) x.get("label"),
                                        (String) x.get("href")));
                    }
                    return new Snapshot(
                            scope,
                            version,
                            (String) raw.get("url"),
                            (String) raw.get("title"),
                            (String) raw.get("text"),
                            links,
                            (Boolean) raw.get("up"),
                            (Boolean) raw.get("down"),
                            ((Number) raw.get("omitted")).intValue());
                });
    }

    @Override
    public Mono<Receipt> execute(Permit permit, Snapshot expected, Action action) {
        return task(
                () -> {
                    if (!permit.consume()) return new Receipt(Outcome.REJECTED, "PERMIT_CONSUMED");
                    if (!scope.equals(expected.scope())
                            || !markers.containsKey(expected.version())
                            || controller == null)
                        return new Receipt(Outcome.STALE, "UNKNOWN_SNAPSHOT");
                    if (action.operation() == Operation.DONE
                            || action.operation() == Operation.BLOCKED)
                        return new Receipt(Outcome.REJECTED, "TERMINAL_HAS_NO_INPUT");
                    if (action.operation() == Operation.CLICK
                            && expected.links().stream()
                                    .noneMatch(l -> l.id().equals(action.target())))
                        return new Receipt(Outcome.REJECTED, "UNKNOWN_TARGET");
                    if (closed.get()) return new Receipt(Outcome.REJECTED, "BROWSER_CLOSED");
                    try {
                        Object result =
                                controller.evaluate(
                                        "(c,a) => c.execute(a)",
                                        Map.of(
                                                "marker",
                                                markers.get(expected.version()),
                                                "operation",
                                                action.operation().name(),
                                                "target",
                                                action.target() == null ? "" : action.target()));
                        String status = String.valueOf(result);
                        return new Receipt(
                                Outcome.valueOf(status),
                                status.equals("APPLIED") ? "INPUT_DISPATCHED" : "GUARD_REJECTED");
                    } catch (RuntimeException e) {
                        return new Receipt(Outcome.UNKNOWN, "BROWSER_DISPATCH_OUTCOME_UNKNOWN");
                    }
                });
    }

    @Override
    public Mono<Void> close() {
        closed.set(true);
        closing.tryEmitValue(true);
        return cleanup;
    }

    private static String script() {
        try (var in = JevPlaywrightSession.class.getResourceAsStream("/jev/browser-snapshot.js")) {
            if (in == null) throw new IllegalStateException("missing browser script");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
