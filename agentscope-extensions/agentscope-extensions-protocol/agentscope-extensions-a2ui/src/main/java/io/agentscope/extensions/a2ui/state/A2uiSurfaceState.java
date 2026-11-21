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
package io.agentscope.extensions.a2ui.state;

import io.agentscope.core.state.State;
import java.util.List;

/**
 * Session-level registry of A2UI surface ids, persisted under
 * {@code AgentStateStore} key {@code "a2ui_surfaces"}. Insertion order defines the surface
 * ordinal used to derive ids.
 */
public record A2uiSurfaceState(List<String> surfaceIds) implements State {

    public A2uiSurfaceState {
        surfaceIds = surfaceIds == null ? List.of() : List.copyOf(surfaceIds);
    }

    public static A2uiSurfaceState empty() {
        return new A2uiSurfaceState(List.of());
    }

    public A2uiSurfaceState withAdded(String surfaceId) {
        if (surfaceIds.contains(surfaceId)) {
            return this;
        }
        List<String> next = new java.util.ArrayList<>(surfaceIds);
        next.add(surfaceId);
        return new A2uiSurfaceState(next);
    }
}
