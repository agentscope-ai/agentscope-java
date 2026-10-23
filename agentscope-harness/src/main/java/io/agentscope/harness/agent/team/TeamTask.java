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
package io.agentscope.harness.agent.team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared team task board item. */
public record TeamTask(
        String taskId,
        String teamName,
        String namespace,
        String subject,
        String description,
        String state,
        String owner,
        List<String> blockedBy,
        String result,
        long version) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    /** {@code true} when the state accepts no further transitions. */
    public static boolean isTerminal(String state) {
        return COMPLETED.equals(state) || FAILED.equals(state);
    }

    /**
     * Unblocked pending tasks claimable by {@code forMember}: unassigned open-board items, and when
     * {@code forMember} is non-blank also pending tasks assigned to that member.
     */
    public static List<TeamTask> claimableOf(List<TeamTask> tasks, String forMember) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> completed = new HashMap<>();
        for (TeamTask t : tasks) {
            if (COMPLETED.equals(t.state())) {
                completed.put(t.taskId(), true);
            }
        }
        String me = forMember == null ? "" : forMember.trim();
        ArrayList<TeamTask> out = new ArrayList<>();
        for (TeamTask t : tasks) {
            if (!PENDING.equals(t.state())) {
                continue;
            }
            String owner = t.owner() == null ? "" : t.owner().trim();
            if (!owner.isEmpty() && (me.isEmpty() || !owner.equals(me))) {
                continue;
            }
            boolean blocked = false;
            for (String b : t.blockedBy() == null ? List.<String>of() : t.blockedBy()) {
                if (!Boolean.TRUE.equals(completed.get(b))) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                out.add(t);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static TeamTask fromMap(Map<String, Object> m) {
        List<String> blocked = List.of();
        Object raw = m.get("blockedBy");
        if (raw instanceof List<?> list) {
            blocked = list.stream().map(String::valueOf).toList();
        }
        return new TeamTask(
                str(m.get("taskId")),
                str(m.get("teamName")),
                str(m.get("namespace")),
                str(m.get("subject")),
                str(m.get("description")),
                str(m.get("state")),
                str(m.get("owner")),
                blocked,
                str(m.get("result")),
                asLong(m.get("version")));
    }

    Map<String, Object> toMap() {
        return Map.ofEntries(
                Map.entry("taskId", nullToEmpty(taskId)),
                Map.entry("teamName", nullToEmpty(teamName)),
                Map.entry("namespace", nullToEmpty(namespace)),
                Map.entry("subject", nullToEmpty(subject)),
                Map.entry("description", nullToEmpty(description)),
                Map.entry("state", nullToEmpty(state)),
                Map.entry("owner", nullToEmpty(owner)),
                Map.entry("blockedBy", blockedBy == null ? List.of() : blockedBy),
                Map.entry("result", nullToEmpty(result)),
                Map.entry("version", version));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
