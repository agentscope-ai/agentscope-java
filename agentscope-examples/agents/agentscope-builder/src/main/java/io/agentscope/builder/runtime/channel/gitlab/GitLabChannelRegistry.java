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
package io.agentscope.builder.runtime.channel.gitlab;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide lookup table from {@code channelId} to {@link GitLabChannel} instance. Used by
 * {@link GitLabWebhookController} to dispatch URL-routed requests onto the correct channel.
 */
public final class GitLabChannelRegistry {

    private static final GitLabChannelRegistry INSTANCE = new GitLabChannelRegistry();

    private final ConcurrentHashMap<String, GitLabChannel> channels = new ConcurrentHashMap<>();

    private GitLabChannelRegistry() {}

    /** Returns the process-wide singleton instance. */
    public static GitLabChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(GitLabChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public GitLabChannel get(String channelId) {
        return channels.get(channelId);
    }
}
