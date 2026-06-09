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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.model.AguiMessage;
import java.util.List;
import java.util.Map;

/**
 * Provides server-side AG-UI snapshots, growing with protocol support for messages, state, and activity.
 */
public interface AguiSnapshotProvider {

    /**
     * A provider that returns empty snapshots.
     */
    AguiSnapshotProvider EMPTY = request -> List.of();

    /**
     * Get a snapshot of the current message history.
     *
     * @param request The snapshot request context
     * @return The current message snapshot
     */
    List<AguiMessage> messagesSnapshot(AguiSnapshotRequest request);

    /**
     * Get a snapshot of the current state.
     *
     * <p>The default implementation returns an empty snapshot until a server-side state source is
     * wired in.
     *
     * @param request The snapshot request context
     * @return The current state snapshot
     */
    default Map<String, Object> stateSnapshot(AguiSnapshotRequest request) {
        return Map.of();
    }

    /**
     * Get a snapshot of the current activity state.
     *
     * <p>The default implementation returns an empty snapshot until activity tracking is wired in.
     *
     * @param request The snapshot request context
     * @return The current activity snapshot
     */
    default Map<String, Object> activitySnapshot(AguiSnapshotRequest request) {
        return Map.of();
    }
}
