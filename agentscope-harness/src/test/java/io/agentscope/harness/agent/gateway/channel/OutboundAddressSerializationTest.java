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
package io.agentscope.harness.agent.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundAddressSerializationTest {

    @Test
    void toMapAndFromMap_roundTripDirectAddress() {
        OutboundAddress original = OutboundAddress.direct("chatui", "chatui:user:alice");
        Map<String, Object> map = original.toMap();
        OutboundAddress restored = OutboundAddress.fromMap(map);

        assertEquals(original, restored);
    }

    @Test
    void toMapAndFromMap_roundTripFullAddress() {
        OutboundAddress original =
                new OutboundAddress("slack", "workspace-1", "slack:CHANNEL:C123", "thread-9");
        OutboundAddress restored = OutboundAddress.fromMap(original.toMap());

        assertEquals(original, restored);
    }

    @Test
    void fromMap_returnsNullForMissingRequiredFields() {
        assertNull(OutboundAddress.fromMap(Map.of("channelId", "x")));
        assertNull(OutboundAddress.fromMap(null));
    }
}
