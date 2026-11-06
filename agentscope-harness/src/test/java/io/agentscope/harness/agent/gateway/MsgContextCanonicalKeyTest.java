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

/** Routing keys must distinguish different conversations and keep a stable wire format. */
class MsgContextCanonicalKeyTest {

    @Test
    void distinctConversationsProduceDistinctCanonicalKeys() {
        MsgContext groupOnly =
                new MsgContext("chatui", "team|r:room-1", null, null, null, Map.of());
        MsgContext groupRoom = new MsgContext("chatui", "team", "room-1", null, null, Map.of());
        assertNotEquals(
                groupOnly.canonicalKey(),
                groupRoom.canonicalKey(),
                "two different routing contexts must not collapse to the same session key");
    }

    @Test
    void extraKeysCannotForgeThroughTheEqualsSeparator() {
        MsgContext valueContainsEquals =
                new MsgContext("c", null, null, null, null, Map.of("a", "b=c"));
        MsgContext keyContainsEquals =
                new MsgContext("c", null, null, null, null, Map.of("a=b", "c"));
        assertNotEquals(
                valueContainsEquals.canonicalKey(),
                keyContainsEquals.canonicalKey(),
                "an extra key containing '=' must not become indistinguishable from a value");
    }

    @Test
    void canonicalKeyFormatIsPinned() {
        MsgContext ctx =
                new MsgContext("chatui", "team", "room-1", "t1", "ts1", Map.of("agentId", "main"));
        assertEquals("chatui|g:team|r:room-1|t:t1|ts:ts1|x:agentId=main", ctx.canonicalKey());
    }

    @Test
    void reservedCharactersAreEscapedInEveryComponent() {
        MsgContext ctx = new MsgContext("a%b", "g|1", "r=2", "t%3", "ts|4", Map.of("k=x", "v|y"));
        assertEquals("a%25b|g:g%7C1|r:r=2|t:t%253|ts:ts%7C4|x:k%3Dx=v%7Cy", ctx.canonicalKey());
    }
}
