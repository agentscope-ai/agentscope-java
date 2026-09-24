/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.converter;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.List;
import java.util.Map;

/**
 * Converter for AG-UI activity events, mirroring {@link AguiStateConverter}.
 *
 * <p>Creates {@link AguiEvent.ActivitySnapshot} and {@link AguiEvent.ActivityDelta} events from
 * before/after activity content maps. Delta computation reuses {@link AguiJsonDiff} so activity
 * deltas are byte-compatible with state deltas.
 */
public class AguiActivityConverter {

    /**
     * Create an {@link AguiEvent.ActivitySnapshot} event.
     *
     * @param threadId the thread id
     * @param runId the run id
     * @param messageId the message id the activity is attached to
     * @param activityType the activity type
     * @param content the activity content
     * @param replace whether the snapshot replaces prior content for this (messageId, activityType)
     * @return the activity snapshot event
     */
    public AguiEvent.ActivitySnapshot createSnapshot(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            Map<String, Object> content,
            boolean replace) {
        return new AguiEvent.ActivitySnapshot(
                threadId, runId, messageId, activityType, content, replace);
    }

    /**
     * Create an {@link AguiEvent.ActivityDelta} event by comparing before and after content.
     *
     * @param threadId the thread id
     * @param runId the run id
     * @param messageId the message id the activity is attached to
     * @param activityType the activity type
     * @param before the activity content before changes
     * @param after the activity content after changes
     * @return the activity delta event, or null if there are no changes
     */
    public AguiEvent.ActivityDelta createDelta(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            Map<String, Object> before,
            Map<String, Object> after) {
        List<JsonPatchOperation> operations = AguiJsonDiff.computeDelta(before, after, "");
        if (operations.isEmpty()) {
            return null;
        }
        return new AguiEvent.ActivityDelta(threadId, runId, messageId, activityType, operations);
    }

    /**
     * Check if there are any differences between two activity content maps.
     *
     * @param before the content before changes
     * @param after the content after changes
     * @return true if there are differences
     */
    public boolean hasChanges(Map<String, Object> before, Map<String, Object> after) {
        return AguiJsonDiff.hasChanges(before, after);
    }
}
