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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Only the host supplies the authorized starting page, session and independent verifier. */
public final class JevBrowserReadTool {
    public record Result(
            String status, String url, String version, String visibleText, List<String> evidence) {}

    private final JevBrowserNavigator navigator;

    public JevBrowserReadTool(JevBrowserNavigator navigator) {
        this.navigator = Objects.requireNonNull(navigator);
    }

    @Tool(
            name = "read_browser",
            readOnly = true,
            description =
                    "Read host-authorized pages to find information. No forms or writes. Only"
                        + " VERIFIED has independent completion evidence. Other states must not be"
                        + " represented as a completed task.")
    public Mono<Result> read(
            @ToolParam(name = "goal", description = "Information to find on authorized pages")
                    String goal,
            RuntimeContext context) {
        return Mono.defer(
                () -> {
                    var source =
                            context == null ? null : context.get(JevBrowserSession.Source.class);
                    if (source == null)
                        return Mono.error(new IllegalStateException("BROWSER_SOURCE_REQUIRED"));
                    return navigator
                            .navigate(context, goal, source)
                            .map(
                                    r -> {
                                        var page = r.page();
                                        boolean enforced =
                                                navigator.mode() == JevExecution.Mode.ENFORCE;
                                        return new Result(
                                                !enforced && page != null ? "OBSERVED" : r.status(),
                                                page == null ? null : page.url(),
                                                page == null ? null : page.version(),
                                                page == null ? "" : page.text(),
                                                enforced && r.status().equals("VERIFIED")
                                                        ? r.verification().evidence()
                                                        : List.of());
                                    });
                });
    }
}
