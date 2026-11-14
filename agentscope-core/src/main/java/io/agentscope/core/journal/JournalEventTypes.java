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
package io.agentscope.core.journal;

import java.util.Set;

/** Native event vocabulary. Public transport names and cursors are independently versioned. */
public final class JournalEventTypes {
    private JournalEventTypes() {}

    public static final Set<String> BUILTIN =
            Set.of(
                    "run/start",
                    "run/end",
                    "run/output",
                    "run/stop_requested",
                    "step/start",
                    "step/phase",
                    "step/end",
                    "input/received",
                    "input/applied",
                    "input/discarded",
                    "message/system",
                    "message/user",
                    "message/assistant",
                    "context/build",
                    "request/prepared",
                    "model/dispatch",
                    "model/end",
                    "model/chunk",
                    "model/retry",
                    "model/request_started",
                    "model/request_ended",
                    "model/request_usage",
                    "tool/requested",
                    "tool/decision",
                    "action/start",
                    "action/end",
                    "tool/dispatch",
                    "tool/chunk",
                    "tool/result",
                    "interaction/requested",
                    "interaction/resolved",
                    "compaction/start",
                    "compaction/end",
                    "context/replaced",
                    "task/changed",
                    "plan/changed",
                    "permission/changed",
                    "verification/result",
                    "subagent/spawned",
                    "subagent/completed",
                    "subagent/linked",
                    "recovery/applied",
                    "migration/baseline",
                    "state/initialized",
                    "presentation/hint",
                    "presentation/text",
                    "presentation/tool_arguments",
                    "presentation/tool_result",
                    "state/checkpoint",
                    "state/restored");

    public static void validate(JournalEvent event) {
        JournalEventCodecRegistry.defaultRegistry().validate(event);
    }
}
