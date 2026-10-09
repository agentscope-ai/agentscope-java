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
package io.agentscope.extensions.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link DingTalkChannelProperties} parsing and validation. */
class DingTalkChannelPropertiesTest {

    @Test
    void defaultsToStreamMode() {
        DingTalkChannelProperties props =
                DingTalkChannelProperties.from(
                        "c1", Map.of("appKey", "k", "appSecret", "s", "robotCode", "r"));
        assertEquals(DingTalkChannelProperties.MODE_STREAM, props.mode());
    }

    @Test
    void readsHttpModeAndAesKey() {
        DingTalkChannelProperties props =
                DingTalkChannelProperties.from(
                        "c1",
                        Map.of(
                                "appKey", "k",
                                "appSecret", "s",
                                "robotCode", "r",
                                "mode", "http",
                                "aesKey", "0123456789012345678901234567890123456789012"));
        assertEquals(DingTalkChannelProperties.MODE_HTTP, props.mode());
        assertEquals("0123456789012345678901234567890123456789012", props.aesKey());
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DingTalkChannelProperties.from(
                                "c1",
                                Map.of(
                                        "appKey", "k",
                                        "appSecret", "s",
                                        "robotCode", "r",
                                        "mode", "grpc")));
    }

    @Test
    void rejectsBadAesKeyLength() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DingTalkChannelProperties.from(
                                "c1",
                                Map.of(
                                        "appKey", "k",
                                        "appSecret", "s",
                                        "robotCode", "r",
                                        "aesKey", "too-short")));
    }
}
