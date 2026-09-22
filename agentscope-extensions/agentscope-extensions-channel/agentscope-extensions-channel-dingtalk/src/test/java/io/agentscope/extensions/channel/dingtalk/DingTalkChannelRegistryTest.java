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

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.SECRET;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DingTalkChannelRegistry}: collision semantics and instance-bound removal. */
class DingTalkChannelRegistryTest {

    private final DingTalkChannelRegistry registry = DingTalkChannelRegistry.instance();
    private final List<DingTalkChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        channels.forEach(DingTalkChannel::stop);
        channels.clear();
    }

    @Test
    void channelRegistersOnStartAndDetachesOnStop() {
        DingTalkChannel channel = startChannel("dt-reg-1");
        assertSame(channel, registry.get("dt-reg-1"));
        channel.stop();
        assertNull(registry.get("dt-reg-1"));
    }

    @Test
    void duplicateChannelIdFailsFastAndKeepsOriginalRegistration() {
        DingTalkChannel first = startChannel("dt-reg-2");
        DingTalkChannel second = buildChannel("dt-reg-2");
        assertThrows(IllegalStateException.class, second::start);
        assertSame(first, registry.get("dt-reg-2"));
    }

    @Test
    void restartingSameChannelIsIdempotent() {
        DingTalkChannel channel = startChannel("dt-reg-3");
        channel.start();
        assertSame(channel, registry.get("dt-reg-3"));
    }

    @Test
    void unregisterOnlyRemovesTheMatchingInstance() {
        DingTalkChannel first = startChannel("dt-reg-4");
        DingTalkChannel second = startChannel("dt-reg-5");
        registry.unregister("dt-reg-5", first);
        assertSame(second, registry.get("dt-reg-5"));
        registry.unregister("dt-reg-5", second);
        assertNull(registry.get("dt-reg-5"));
        assertSame(first, registry.get("dt-reg-4"));
    }

    private DingTalkChannel startChannel(String channelId) {
        DingTalkChannel channel = buildChannel(channelId);
        channel.start();
        channels.add(channel);
        return channel;
    }

    private static DingTalkChannel buildChannel(String channelId) {
        return DingTalkChannel.fromProperties(
                channelId,
                ChannelConfig.of(channelId, "main"),
                Map.of(
                        "appKey", "app-key",
                        "appSecret", SECRET,
                        "robotCode", "robot",
                        "mode", "http"));
    }
}
