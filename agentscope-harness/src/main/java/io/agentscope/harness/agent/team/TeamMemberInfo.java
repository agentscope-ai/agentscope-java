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

import java.util.Map;

/** Team member roster entry. */
public record TeamMemberInfo(
        String memberName,
        String agentRef,
        String phase,
        String sessionId,
        String deployMode,
        boolean isLead) {

    static TeamMemberInfo fromMap(Map<String, Object> m) {
        return new TeamMemberInfo(
                str(m.get("memberName")),
                str(m.get("agentRef")),
                str(m.get("phase")),
                str(m.get("sessionId")),
                str(m.get("deployMode")),
                Boolean.TRUE.equals(m.get("isLead")) || "lead".equals(str(m.get("memberName"))));
    }

    Map<String, Object> toMap() {
        return Map.of(
                "memberName", nullToEmpty(memberName),
                "agentRef", nullToEmpty(agentRef),
                "phase", nullToEmpty(phase),
                "sessionId", nullToEmpty(sessionId),
                "deployMode", nullToEmpty(deployMode),
                "isLead", isLead);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
