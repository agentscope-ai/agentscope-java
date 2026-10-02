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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Expands {@code @path} references in user messages by reading the referenced files through the
 * active {@link AbstractFilesystem} and appending an {@code <attached_file>} block at the end of
 * the message. Mirrors the Claude Code CLI's {@code @file} input syntax.
 *
 * <p>Dispatch by filesystem type (kept as in-line {@code instanceof} since there is exactly one
 * call site):
 *
 * <ul>
 *   <li><b>Local</b> ({@link OverlayFilesystem} wrapping {@link LocalFilesystemWithShell}) —
 *       attempts {@code fs.read}; success triggers attachment, failure (typically because the
 *       path falls outside the {@code PathPolicy} allow-list) leaves the {@code @path} text
 *       untouched.
 *   <li><b>Sandbox</b> ({@link AbstractSandboxFilesystem}, not wrapped in overlay) — treats the
 *       reference as a path inside the sandbox; host-path upload is out of scope for this stage.
 *   <li><b>Remote</b> ({@link CompositeFilesystem}) — disabled. The agent is not co-located with
 *       the user so {@code @path} has no meaningful resolution; references are left as-is.
 * </ul>
 *
 * <p>Recognises three reference shapes: absolute ({@code @/abs/path}), relative
 * ({@code @./rel} or {@code @rel/foo}), and home-anchored ({@code @~/file}). A bare {@code @word}
 * with no path-like character (slash, dot, tilde) is ignored to avoid swallowing handles such as
 * {@code @alice}.
 */
public class AtPathExpansionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AtPathExpansionMiddleware.class);

    /**
     * Matches an {@code @} immediately followed by a path token. Tokens must contain at least one
     * path indicator (slash, dot, or tilde) to avoid swallowing user handles. Trailing
     * punctuation ({@code . , ; : ! ? CJK marks}) is excluded so sentence boundaries don't end
     * up inside the captured path.
     */
    private static final Pattern AT_PATH =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])"
                            + "@(?<path>[A-Za-z]:[\\\\/][\\w\\\\./\\-~]*"
                            + "|[~./][\\w./\\-~]*"
                            + "|/[\\w./\\-~]+"
                            + "|[\\w\\-]+[/.][\\w./\\-~]*)");

    /** Cap the bytes pulled in per attached file so a stray {@code @log} doesn't blow context. */
    private static final int MAX_ATTACHED_LINES = 1000;

    private final WorkspaceManager workspaceManager;

    public AtPathExpansionMiddleware(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (!supportsExpansion(fs)) {
            return next.apply(input);
        }

        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();

        List<Msg> rewritten = new ArrayList<>(input.msgs().size());
        boolean changed = false;
        for (Msg msg : input.msgs()) {
            if (msg.getRole() != MsgRole.USER) {
                rewritten.add(msg);
                continue;
            }
            Msg expanded = expand(msg, fs, rc);
            if (expanded != msg) {
                changed = true;
            }
            rewritten.add(expanded);
        }
        return next.apply(changed ? new AgentInput(rewritten) : input);
    }

    private boolean supportsExpansion(AbstractFilesystem fs) {
        if (fs == null) {
            return false;
        }
        // Remote (CompositeFilesystem) — distributed deployment, no host paths.
        if (fs instanceof CompositeFilesystem) {
            return false;
        }
        return true;
    }

    private Msg expand(Msg msg, AbstractFilesystem fs, RuntimeContext rc) {
        String text = msg.getTextContent();
        if (text == null || text.indexOf('@') < 0) {
            return msg;
        }

        Matcher m = AT_PATH.matcher(text);
        Map<String, String> attached = new LinkedHashMap<>();
        while (m.find()) {
            String ref = m.group("path");
            if (attached.containsKey(ref)) {
                continue;
            }
            String content = tryRead(fs, rc, ref);
            if (content != null) {
                attached.put(ref, content);
            }
        }
        if (attached.isEmpty()) {
            return msg;
        }

        StringBuilder sb = new StringBuilder(text);
        for (Map.Entry<String, String> e : attached.entrySet()) {
            sb.append("\n\n<attached_file path=\"")
                    .append(e.getKey())
                    .append("\">\n")
                    .append(e.getValue())
                    .append(e.getValue().endsWith("\n") ? "" : "\n")
                    .append("</attached_file>");
        }
        return msg.withContent(List.of(TextBlock.builder().text(sb.toString()).build()));
    }

    private String tryRead(AbstractFilesystem fs, RuntimeContext rc, String ref) {
        String resolved = expandHome(ref);
        try {
            ReadResult r = fs.read(rc, resolved, 0, MAX_ATTACHED_LINES);
            if (r.isSuccess() && r.fileData() != null && r.fileData().content() != null) {
                String content = r.fileData().content();
                // Skip binary attachments — base64 in a user message is rarely useful and just
                // burns tokens.
                if ("base64".equals(r.fileData().encoding())) {
                    return null;
                }
                return content;
            }
        } catch (SecurityException e) {
            log.debug("@-path expansion refused for {} ({})", ref, e.getMessage());
        } catch (Exception e) {
            log.debug("@-path expansion failed for {}: {}", ref, e.getMessage());
        }
        return null;
    }

    private static String expandHome(String ref) {
        if (ref.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return home != null ? home + ref.substring(1) : ref;
        }
        return ref;
    }
}
