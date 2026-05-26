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
package io.agentscope.dataagent.runtime.gateway;

import io.agentscope.core.message.Msg;
import io.agentscope.dataagent.runtime.channel.Channel;
import io.agentscope.dataagent.runtime.channel.OutboundAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry and lifecycle manager for {@link Channel} adapters. Provides outbound dispatch so the
 * gateway can deliver proactive messages (e.g. subagent announces) through the correct channel.
 *
 * <p>Mirrors OpenClaw's {@code createChannelManager} which handles channel start/stop and outbound
 * delivery routing.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #register(Channel)} — add a channel to the registry
 *   <li>{@link #initAll(Gateway)} — inject the gateway into all registered channels
 *   <li>{@link #startAll()} — start all channels (connect to external transports)
 *   <li>{@link #stopAll()} — stop all channels and release resources
 * </ol>
 *
 * <h2>Outbound delivery</h2>
 *
 * {@link #deliver(OutboundAddress, List)} looks up the target channel by {@link
 * OutboundAddress#channelId()} and delegates to {@link Channel#deliver(OutboundAddress, List)}.
 */
public final class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public ChannelManager() {}

    /**
     * Registers a channel adapter. If a channel with the same {@link Channel#channelId()} is
     * already registered, it is replaced.
     */
    public void register(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        channels.put(channel.channelId(), channel);
    }

    /**
     * Stops and removes the channel registered under {@code channelId}, if any. Returns
     * {@code true} when a channel was removed, {@code false} when no channel was registered under
     * that id. Errors raised by {@link Channel#stop()} are logged but do not prevent removal.
     */
    public boolean unregister(String channelId) {
        if (channelId == null) {
            return false;
        }
        Channel removed = channels.remove(channelId);
        if (removed == null) {
            return false;
        }
        try {
            removed.stop();
            log.info("Channel unregistered and stopped: {}", channelId);
        } catch (Exception e) {
            log.warn(
                    "Error stopping channel '{}' during unregister: {}",
                    channelId,
                    e.getMessage(),
                    e);
        }
        return true;
    }

    /** Returns the channel registered under the given id, if any. */
    public Optional<Channel> getChannel(String channelId) {
        if (channelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(channels.get(channelId));
    }

    /** Returns an unmodifiable snapshot of all registered channel ids. */
    public List<String> channelIds() {
        return List.copyOf(channels.keySet());
    }

    /** Returns an unmodifiable snapshot of all registered channels. */
    public java.util.Collection<Channel> getAllChannels() {
        return List.copyOf(channels.values());
    }

    /**
     * Injects the gateway into all registered channels via {@link Channel#init(Gateway)}. Called
     * once during bootstrap before {@link #startAll()}.
     */
    public void initAll(Gateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        for (Channel ch : channels.values()) {
            try {
                ch.init(gateway);
            } catch (Exception e) {
                log.error("Failed to init channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /** Starts all registered channels. Channels that fail to start are logged but do not abort. */
    public void startAll() {
        for (Channel ch : channels.values()) {
            try {
                ch.start();
                log.info("Channel started: {}", ch.channelId());
            } catch (Exception e) {
                log.error("Failed to start channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
        started = true;
    }

    /** Stops all registered channels and clears the registry. */
    public void stopAll() {
        started = false;
        for (Channel ch : channels.values()) {
            try {
                ch.stop();
                log.info("Channel stopped: {}", ch.channelId());
            } catch (Exception e) {
                log.warn("Error stopping channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /** Whether {@link #startAll()} has been called. */
    public boolean isStarted() {
        return started;
    }

    /**
     * Delivers proactive outbound messages through the channel identified by {@link
     * OutboundAddress#channelId()}. If the target channel is not registered or does not support
     * outbound delivery, the messages are dropped with a warning log.
     *
     * @param address the delivery target
     * @param messages the messages to deliver
     */
    public void deliver(OutboundAddress address, List<Msg> messages) {
        Objects.requireNonNull(address, "address");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Channel ch = channels.get(address.channelId());
        if (ch == null) {
            log.warn(
                    "Outbound delivery skipped: no channel registered for '{}'",
                    address.channelId());
            return;
        }
        try {
            ch.deliver(address, messages);
        } catch (Exception e) {
            log.error(
                    "Outbound delivery failed: channel='{}', to='{}': {}",
                    address.channelId(),
                    address.to(),
                    e.getMessage(),
                    e);
        }
    }
}
