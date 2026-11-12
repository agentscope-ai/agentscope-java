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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;

/** Business depth limits from frozen Session configuration, independent of transport routing. */
public final class ManagedTurnLanes {
    public static final int MAX_DEPTH = 16;

    private ManagedTurnLanes() {}

    public static int depth(String configuration, ObjectMapper mapper) {
        try {
            var node = mapper.readTree(configuration).get("subagent");
            if (node == null) return 0;
            var depth = node.get("depth");
            if (depth == null
                    || !depth.isIntegralNumber()
                    || !depth.canConvertToInt()
                    || depth.intValue() < 1
                    || depth.intValue() > MAX_DEPTH)
                throw new IllegalArgumentException("Invalid frozen child Session depth");
            return depth.intValue();
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid frozen Session configuration", invalid);
        }
    }

    public static void validate(Channel channel, int depth) {
        if (depth < 0 || depth > MAX_DEPTH || (channel != Channel.TURNS && depth != 0))
            throw new IllegalArgumentException("Invalid Managed channel depth");
    }
}
