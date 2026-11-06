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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link MsgContext#canonicalKey()} is the gateway session key: the session id, the per-key turn
 * gate and the session-to-key wake-up map all derive from it. Two distinct conversations must
 * therefore never collapse onto one key.
 */
class MsgContextCanonicalKeyTest {

    @Test
    void separatorInsideGroupCannotForgeAnotherComponent() {
        // The reported collision: a group id that already spells out "|r:<room>" produced the same
        // key as an explicit group plus room.
        MsgContext groupOnly =
                new MsgContext("chatui", "team|r:room-1", null, null, null, Map.of());
        MsgContext groupRoom = new MsgContext("chatui", "team", "room-1", null, null, Map.of());

        assertNotEquals(groupOnly.canonicalKey(), groupRoom.canonicalKey());
    }

    @Test
    void separatorInsideChannelCannotForgeALabel() {
        MsgContext channelOnly = new MsgContext("chatui|g:team", null, null, null, null, Map.of());
        MsgContext channelAndGroup = new MsgContext("chatui", "team", null, null, null, Map.of());

        assertNotEquals(channelOnly.canonicalKey(), channelAndGroup.canonicalKey());
    }

    @Test
    void extraValueCannotForgeAnotherEntry() {
        // A single entry whose value reads like a second entry used to produce the same key as the
        // two real entries.
        MsgContext forged =
                new MsgContext("chatui", null, null, null, null, Map.of("a", "b|x:c=d"));
        MsgContext real =
                new MsgContext("chatui", null, null, null, null, Map.of("a", "b", "c", "d"));

        assertNotEquals(forged.canonicalKey(), real.canonicalKey());
    }

    @Test
    void literalEscapeDoesNotCollideWithEscapedSeparator() {
        // '%' must be escaped before '|', otherwise a value that literally contains "%7C" maps onto
        // the same key as one containing "|".
        MsgContext withSeparator = new MsgContext("chatui", "a|b", null, null, null, Map.of());
        MsgContext withLiteralEscape =
                new MsgContext("chatui", "a%7Cb", null, null, null, Map.of());

        assertNotEquals(withSeparator.canonicalKey(), withLiteralEscape.canonicalKey());
        assertEquals("chatui|g:a%257Cb", withLiteralEscape.canonicalKey());
    }

    @Test
    void separatorFreeComponentsKeepTheirExistingKeyByteForByte() {
        // The escape is conditional, so ordinary ids - which carry neither '%' nor '|' - must not
        // change. Session ids are a deterministic hash of this key, so an unchanged key means no
        // session migration for existing deployments.
        MsgContext ctx =
                new MsgContext(
                        "chatui",
                        "team",
                        "room-1",
                        "thread-9",
                        "1727000000",
                        Map.of("agentId", "assistant"));

        assertEquals(
                "chatui|g:team|r:room-1|t:thread-9|ts:1727000000|x:agentId=assistant",
                ctx.canonicalKey());
    }

    @Test
    void sameContextStillMapsToTheSameSessionId() {
        MsgContext first = new MsgContext("chatui", "team", "room|1", null, null, Map.of());
        MsgContext second = new MsgContext("chatui", "team", "room|1", null, null, Map.of());

        assertEquals(
                SessionIdUtils.deterministicHash(first.canonicalKey()),
                SessionIdUtils.deterministicHash(second.canonicalKey()));
    }
}
