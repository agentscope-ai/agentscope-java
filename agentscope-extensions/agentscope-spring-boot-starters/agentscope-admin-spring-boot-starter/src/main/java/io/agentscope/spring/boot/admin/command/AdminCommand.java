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
package io.agentscope.spring.boot.admin.command;

import java.util.List;

/**
 * Self-describing record of an admin operation exposed by this starter.
 *
 * <p>The unified registry powers three views with the same metadata: REST routes, Actuator
 * endpoints, and a machine-readable {@code /actuator/agentscope-commands} catalog used by CLI /
 * Studio / Kubernetes operator clients.
 *
 * @param id stable internal id, e.g. {@code session.compact}
 * @param title human-friendly label, e.g. "Compact session"
 * @param category coarse grouping, e.g. {@code Session}, {@code Agent}, {@code System}
 * @param plane DATA = per-session REST; CONTROL = Actuator
 * @param httpMethod HTTP verb the command is reached with, e.g. {@code GET}, {@code POST}
 * @param httpPath full route from the application root, e.g.
 *     {@code /v1/admin/sessions/{sessionId}:compact}
 * @param aliases extra display aliases (CLI-style, e.g. {@code summarize})
 * @param writes whether the command mutates state — disabled unless
 *     {@code agentscope.admin.write-enabled=true}
 * @param idempotent whether repeated invocation is safe
 * @param description short one-liner shown in the catalog
 */
public record AdminCommand(
        String id,
        String title,
        String category,
        CommandPlane plane,
        String httpMethod,
        String httpPath,
        List<String> aliases,
        boolean writes,
        boolean idempotent,
        String description) {

    public AdminCommand {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.title must not be blank");
        }
        if (plane == null) {
            throw new IllegalArgumentException("AdminCommand.plane must not be null");
        }
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.httpMethod must not be blank");
        }
        if (httpPath == null || httpPath.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.httpPath must not be blank");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        category = category == null ? "Misc" : category;
        description = description == null ? "" : description;
    }
}
