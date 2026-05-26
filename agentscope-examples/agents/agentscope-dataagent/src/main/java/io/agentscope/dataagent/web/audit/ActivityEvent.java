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
package io.agentscope.dataagent.web.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * A single entry in an agent's per-namespace activity log.
 *
 * <p>Stored append-only as one JSON object per line in {@code activity.jsonl} within the agent's
 * namespaced workspace (so isolation rules apply automatically). See plan §1.6 / §G.
 *
 * @param id stable identifier (ULID or random hex)
 * @param timestampMs epoch-millis when the event was recorded
 * @param actorUserId userId of the actor who performed the action
 * @param actorUsername resolved display username, or {@code null} if unknown at log time
 * @param action high-level event class — see {@link Action} for the canonical set
 * @param target optional event-specific target (file path, channel id, sessionKey, granteeId, ...)
 * @param metadata optional structured detail; serialized as a JSON object
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityEvent(
        String id,
        long timestampMs,
        String actorUserId,
        String actorUsername,
        String action,
        String target,
        Map<String, Object> metadata) {

    /** Canonical action labels. Adding new ones is fine; readers should be tolerant. */
    public static final class Action {
        public static final String CREATE = "CREATE";
        public static final String EDIT_SETTINGS = "EDIT_SETTINGS";
        public static final String DELETE_AGENT = "DELETE_AGENT";
        public static final String EDIT_FILE = "EDIT_FILE";
        public static final String CREATE_FILE = "CREATE_FILE";
        public static final String DELETE_FILE = "DELETE_FILE";
        public static final String RENAME_FILE = "RENAME_FILE";
        public static final String UPLOAD_FILE = "UPLOAD_FILE";
        public static final String GRANT_SHARE = "GRANT_SHARE";
        public static final String REVOKE_SHARE = "REVOKE_SHARE";
        public static final String CLONE_FROM = "CLONE_FROM";
        public static final String CLONE_TO = "CLONE_TO";
        public static final String BIND_CHANNEL = "BIND_CHANNEL";
        public static final String UNBIND_CHANNEL = "UNBIND_CHANNEL";
        public static final String EDIT_BINDING = "EDIT_BINDING";
        public static final String RUN_SESSION = "RUN_SESSION";

        private Action() {}
    }
}
