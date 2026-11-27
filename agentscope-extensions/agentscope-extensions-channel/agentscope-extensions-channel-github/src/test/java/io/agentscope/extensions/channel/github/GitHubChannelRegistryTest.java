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
package io.agentscope.extensions.channel.github;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real channel stop calls must not detach a different instance's callbacks. */
class GitHubChannelRegistryTest {
    private final GitHubChannelRegistry registry = GitHubChannelRegistry.instance();
    private final String channelId = "registry-test-" + UUID.randomUUID();

    @AfterEach
    void clearRegistration() {
        registry.unregister(channelId);
    }

    @Test
    void stoppingReplacedChannelPreservesSuccessor() {
        GitHubChannel original = channel();
        GitHubChannel successor = channel();
        registry.register(original);
        registry.register(successor);
        original.stop();
        assertSame(successor, registry.get(channelId));
    }

    @Test
    void stoppingUnregisteredChannelPreservesActiveRegistration() {
        GitHubChannel active = channel();
        registry.register(active);
        channel().stop();
        assertSame(active, registry.get(channelId));
    }

    @Test
    void repeatedStopCannotDetachLaterRegistration() {
        GitHubChannel original = channel();
        registry.register(original);
        original.stop();
        assertNull(registry.get(channelId));
        GitHubChannel successor = channel();
        registry.register(successor);
        original.stop();
        assertSame(successor, registry.get(channelId));
    }

    @Test
    void matchingStopRemovesRegistrationAndCanBeRepeated() {
        GitHubChannel active = channel();
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

    private GitHubChannel channel() {
        return GitHubChannel.fromProperties(
                channelId,
                ChannelConfig.of(channelId, "main"),
                Map.of("token", "test-token", "webhookSecret", "test-secret"));
    }
}
