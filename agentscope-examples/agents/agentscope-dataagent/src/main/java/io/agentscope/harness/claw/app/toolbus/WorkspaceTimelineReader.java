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
package io.agentscope.harness.claw.app.toolbus;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-side companion to {@link WorkspaceMutationHook}. Parses {@code
 * sessions/<sessionId>.workspace.jsonl} (relative to a tenant's workspace root) into typed
 * entries.
 */
public final class WorkspaceTimelineReader {

    private WorkspaceTimelineReader() {}

    /**
     * Resolves the timeline file for a session within a given user workspace root. The
     * {@code userWorkspace} argument is the per-user workspace path returned by
     * {@code UserWorkspaceProvisioner.resolveUserWorkspace(userId)}.
     */
    public static Path resolveTimelinePath(Path userWorkspace, String sessionId) {
        return userWorkspace.resolve("sessions").resolve(sessionId + ".workspace.jsonl");
    }

    /**
     * Reads the latest {@code limit} mutations, newest first. Filters by {@code since} (epoch ms)
     * when non-null. Returns empty list when the file does not exist or cannot be parsed.
     */
    public static List<MutationEntry> readEvents(
            Path userWorkspace, String sessionId, Long since, int limit) {
        Path file = resolveTimelinePath(userWorkspace, sessionId);
        if (!Files.isRegularFile(file)) return Collections.emptyList();
        int cap = Math.max(1, Math.min(limit, 2000));

        List<MutationEntry> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) continue;
                MutationEntry entry = parseLine(line);
                if (entry == null) continue;
                if (since != null && entry.ts() < since) continue;
                all.add(entry);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        all.sort(Comparator.comparingLong(MutationEntry::ts).reversed());
        if (all.size() > cap) return all.subList(0, cap);
        return all;
    }

    private static MutationEntry parseLine(String line) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(line, Map.class);
            if (map == null) return null;
            return new MutationEntry(
                    asLong(map.get("ts")),
                    asString(map.get("sessionKey")),
                    asString(map.get("userId")),
                    asString(map.get("agentId")),
                    asString(map.get("sessionId")),
                    asString(map.get("toolCallId")),
                    asString(map.get("toolName")),
                    asString(map.get("path")),
                    asString(map.get("kind")),
                    asString(map.get("preHash")),
                    asString(map.get("postHash")),
                    asLong(map.get("preSize")),
                    asLong(map.get("postSize")));
        } catch (Exception e) {
            return null;
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    public record MutationEntry(
            long ts,
            String sessionKey,
            String userId,
            String agentId,
            String sessionId,
            String toolCallId,
            String toolName,
            String path,
            String kind,
            String preHash,
            String postHash,
            long preSize,
            long postSize) {}
}
