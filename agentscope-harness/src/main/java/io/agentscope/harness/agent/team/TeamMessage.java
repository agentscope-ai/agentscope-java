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

/** Team mailbox message. */
public record TeamMessage(String from, String to, String content, long id) {
    static TeamMessage fromMap(Map<String, Object> m) {
        long id = 0L;
        Object raw = m.get("id");
        if (raw instanceof Number n) {
            id = n.longValue();
        }
        return new TeamMessage(
                String.valueOf(m.getOrDefault("from", "")),
                String.valueOf(m.getOrDefault("to", "")),
                String.valueOf(m.getOrDefault("content", "")),
                id);
    }
}
