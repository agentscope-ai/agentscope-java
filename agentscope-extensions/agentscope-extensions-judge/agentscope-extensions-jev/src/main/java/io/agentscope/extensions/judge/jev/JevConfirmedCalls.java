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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Call-scoped human confirmation binding. Changing name or arguments invalidates the match. */
public final class JevConfirmedCalls {
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final Map<String, String> approved = new ConcurrentHashMap<>();

    public record Approval(String callId, String fingerprint) {}

    /** Capture before asking the user, so changes while awaiting confirmation invalidate it. */
    public static Approval snapshot(ToolUseBlock call) {
        if (call.getId() == null) return null;
        try {
            return new Approval(call.getId(), fingerprint(call));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void remember(RuntimeContext ctx, ToolUseBlock call) {
        remember(ctx, snapshot(call));
    }

    public static void remember(RuntimeContext ctx, Approval approval) {
        if (ctx == null || approval == null) return;
        synchronized (ctx) {
            var ledger = ctx.get(JevConfirmedCalls.class);
            if (ledger == null) {
                ledger = new JevConfirmedCalls();
                ctx.put(JevConfirmedCalls.class, ledger);
            }
            ledger.approved.put(approval.callId(), approval.fingerprint());
        }
    }

    public static boolean contains(RuntimeContext ctx, ToolUseBlock call) {
        var ledger = ctx == null ? null : ctx.get(JevConfirmedCalls.class);
        return ledger != null && call.getId() != null && ledger.approved.containsKey(call.getId());
    }

    public static boolean matches(RuntimeContext ctx, ToolUseBlock call) {
        var ledger = ctx == null ? null : ctx.get(JevConfirmedCalls.class);
        if (ledger == null || call.getId() == null) return false;
        String expected = ledger.approved.get(call.getId());
        if (expected == null) return false;
        try {
            return expected.equals(fingerprint(call));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String fingerprint(ToolUseBlock call) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            JSON.writeValueAsBytes(
                                                    Map.of(
                                                            "name",
                                                            call.getName(),
                                                            "arguments",
                                                            call.getInput()))));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to bind confirmed tool arguments");
        }
    }
}
