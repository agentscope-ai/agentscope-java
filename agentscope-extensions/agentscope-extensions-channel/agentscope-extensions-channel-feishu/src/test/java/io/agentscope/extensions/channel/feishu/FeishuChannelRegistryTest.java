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
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real channel stop calls must not detach a different instance's callbacks. */
class FeishuChannelRegistryTest {
    private final FeishuChannelRegistry registry = FeishuChannelRegistry.instance();
    private final String channelId = "registry-test-" + UUID.randomUUID();

    @AfterEach
    void clearRegistration() {
        registry.unregister(channelId);
    }

    @Test
    void stoppingReplacedChannelPreservesSuccessor() {
        FeishuChannel original = channel();
        FeishuChannel successor = channel();
        registry.register(original);
        registry.register(successor);
        original.stop();
        assertSame(successor, registry.get(channelId));
    }

    @Test
    void stoppingUnregisteredChannelPreservesActiveRegistration() {
        FeishuChannel active = channel();
        registry.register(active);
        channel().stop();
        assertSame(active, registry.get(channelId));
    }

    @Test
    void repeatedStopCannotDetachLaterRegistration() {
        FeishuChannel original = channel();
        registry.register(original);
        original.stop();
        assertNull(registry.get(channelId));
        FeishuChannel successor = channel();
        registry.register(successor);
        original.stop();
        assertSame(successor, registry.get(channelId));
    }

    @Test
    void matchingStopRemovesRegistrationAndCanBeRepeated() {
        FeishuChannel active = channel();
        registry.register(active);
        active.stop();
        active.stop();
        assertNull(registry.get(channelId));
    }

    @Test
    void explicitUnregisterByIdKeepsExistingBehavior() {
        registry.register(channel());
        registry.unregister(channelId);
        assertNull(registry.get(channelId));
    }

    private FeishuChannel channel() {
        return FeishuChannel.fromProperties(
                channelId,
                ChannelConfig.of(channelId, "main"),
                Map.of("appId", "test-app", "appSecret", "test-secret"));
    }
}
