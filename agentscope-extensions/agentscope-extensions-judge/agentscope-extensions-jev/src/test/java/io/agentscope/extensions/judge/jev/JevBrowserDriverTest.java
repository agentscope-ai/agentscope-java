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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Operation;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Outcome;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevPlaywrightSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.publisher.Mono;

/** Explicit real-browser regression. Test-only reflection mutates an owned fixture on the driver's
 * thread; it does not add a script-evaluation capability to the production tool. */
@EnabledIfSystemProperty(named = "jev.browser", matches = ".+")
class JevBrowserDriverTest {
    @SuppressWarnings("unchecked")
    static Object evaluate(JevPlaywrightSession session, String script) throws Exception {
        var field = JevPlaywrightSession.class.getDeclaredField("page");
        field.setAccessible(true);
        var method = JevPlaywrightSession.class.getDeclaredMethod("task", Callable.class);
        method.setAccessible(true);
        return ((Mono<Object>)
                        method.invoke(
                                session,
                                (Callable<Object>)
                                        () -> ((Page) field.get(session)).evaluate(script)))
                .block();
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void actualDomGuardsAndSingleUseDispatch() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String html =
                """
                <!doctype html><title>Guard fixture</title><style>body{margin:30px}a{display:block;width:200px;height:40px}#outside{position:absolute;top:3000px}</style>
                <p id="context">Warranty help</p><a id="target" href="#detail">Warranty policy</a>
                <input id="field" value="Zurich"><input type="password" value="secret-marker">
                <a href="https://unapproved.example/">unapproved</a><a hidden href="#hidden">hidden</a>
                <div aria-disabled="true"><a href="#disabled">disabled ancestor</a></div>
                    <a href="#download" download>download</a><form><a href="#form">inside form</a></form>
                <p id="outside">Offscreen text</p>
                <script>window.clicks=0;document.addEventListener('click',e=>{if(e.target.id==='target'){e.preventDefault();window.clicks++;}});</script>
                """;
        server.createContext(
                "/",
                e -> {
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var session =
                new JevPlaywrightSession(
                        new Scope("u", "s", "tab"),
                        Path.of(System.getProperty("jev.browser")),
                        url,
                        Set.of(url),
                        Duration.ofSeconds(10));
        try {
            var page = session.observe().block();
            assertEquals(1, page.links().size());
            assertFalse(page.text().contains("secret-marker"));
            var target = page.links().get(0);
            var click = new Action(Operation.CLICK, target.id());
            var permit = new Permit();
            evaluate(
                    session,
                    "document.querySelector('#target').style.transform='translateX(200px)'");
            assertEquals(page.version(), session.observe().block().version());
            assertEquals(Outcome.APPLIED, session.execute(permit, page, click).block().outcome());
            assertEquals(1, ((Number) evaluate(session, "window.clicks")).intValue());
            assertEquals(Outcome.REJECTED, session.execute(permit, page, click).block().outcome());
            assertEquals(1, ((Number) evaluate(session, "window.clicks")).intValue());
            evaluate(
                    session,
                    "document.querySelector('#outside').textContent='Changed outside viewport'");
            assertEquals(page.version(), session.observe().block().version());
            for (String mutation :
                    List.of(
                            "document.querySelector('#context').textContent+=' changed'",
                            "document.querySelector('#target').setAttribute('aria-label','Changed"
                                    + " label')",
                            "document.querySelector('#field').value='London'",
                            "document.querySelector('#target').outerHTML=document.querySelector('#target').outerHTML")) {
                var before = session.observe().block();
                var action = new Action(Operation.CLICK, before.links().get(0).id());
                evaluate(session, mutation);
                assertNotEquals(before.version(), session.observe().block().version());
                assertEquals(
                        Outcome.STALE,
                        session.execute(new Permit(), before, action).block().outcome());
            }
            var before = session.observe().block();
            var action = new Action(Operation.CLICK, before.links().get(0).id());
            evaluate(
                    session,
                    "const"
                        + " cover=document.createElement('div');cover.style.cssText='position:fixed;inset:0;z-index:99999;background:white';document.body.append(cover)");
            assertEquals(before.version(), session.observe().block().version());
            assertEquals(
                    Outcome.REJECTED,
                    session.execute(new Permit(), before, action).block().outcome());
            assertEquals(1, ((Number) evaluate(session, "window.clicks")).intValue());
            assertEquals(
                    Outcome.REJECTED,
                    session.execute(new Permit(), before, new Action(Operation.CLICK, "invented"))
                            .block()
                            .outcome());
            assertEquals(
                    Outcome.REJECTED,
                    session.execute(new Permit(), before, new Action(Operation.DONE, null))
                            .block()
                            .outcome());
            session.close().block();
            assertThrows(IllegalStateException.class, () -> session.observe().block());
            session.close().block();
        } finally {
            session.close().block();
            server.stop(0);
        }
    }
}
