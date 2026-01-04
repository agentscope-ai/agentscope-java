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
package io.agentscope.core.pipeline;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * MsgHub is designed to share messages among a group of agents.
 *
 * <p>MsgHub manages message broadcasting and subscription in multi-agent conversations.
 * When agents are added to a MsgHub, they automatically observe each other's messages
 * without explicit message passing code.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li><b>Automatic Broadcasting:</b> Messages from any participant are automatically
 *       broadcast to all other participants</li>
 *   <li><b>Dynamic Participants:</b> Add or remove agents during conversation</li>
 *   <li><b>Manual Broadcasting:</b> Broadcast messages manually when needed</li>
 *   <li><b>Announcement Support:</b> Send initial messages when entering the hub</li>
 *   <li><b>Lifecycle Management:</b> Automatic cleanup with try-with-resources</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create agents
 * ReActAgent alice = ReActAgent.builder()
 *     .name("Alice")
 *     .model(model)
 *     .formatter(new DashScopeMultiAgentFormatter())
 *     .build();
 *
 * ReActAgent bob = ReActAgent.builder()
 *     .name("Bob")
 *     .model(model)
 *     .formatter(new DashScopeMultiAgentFormatter())
 *     .build();
 *
 * // Use MsgHub for multi-agent conversation
 * Msg announcement = new Msg("system", "Let's start the discussion", MsgRole.SYSTEM);
 *
 * try (MsgHub hub = MsgHub.builder()
 *         .participants(alice, bob)
 *         .announcement(announcement)
 *         .build()) {
 *
 *     hub.enter().block();
 *
 *     // Alice's reply will be automatically broadcast to Bob
 *     alice.call().block();
 *
 *     // Bob's reply will be automatically broadcast to Alice
 *     bob.call().block();
 * }
 * }</pre>
 *
 * <p>This is much simpler than manual message passing:
 * <pre>{@code
 * // Without MsgHub (verbose and error-prone)
 * Msg x1 = alice.call().block();
 * bob.observe(x1).block();
 *
 * Msg x2 = bob.call().block();
 * alice.observe(x2).block();
 * }</pre>
 *
 * <p><b>Thread Safety:</b>
 * MsgHub uses CopyOnWriteArrayList for thread-safe participant management.
 * However, individual agent instances should not be invoked concurrently.
 *
 * @see Agent
 * @see AgentBase#resetSubscribers(String, List)
 * @see AgentBase#removeSubscribers(String)
 */
public class MsgHub implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MsgHub.class);

    private final String name;
    private final List<AgentBase> participants;
    private final List<Msg> announcement;
    private boolean enableAutoBroadcast;
    private boolean entered = false;

    /**
     * Private constructor. Use builder() to create instances.
     *
     * @param builder Builder instance
     */
    private MsgHub(Builder builder) {
        this.name = builder.name != null ? builder.name : UUID.randomUUID().toString();
        this.participants = new CopyOnWriteArrayList<>(builder.participants);
        this.announcement = builder.announcement;
        this.enableAutoBroadcast = builder.enableAutoBroadcast;
    }

    /**
     * Get the name of this MsgHub.
     *
     * @return MsgHub name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the list of current participants.
     *
     * @return Unmodifiable view of participants
     */
    public List<AgentBase> getParticipants() {
        return new ArrayList<>(participants);
    }

    /**
     * Check if auto-broadcast is enabled.
     *
     * @return True if auto-broadcast is enabled
     */
    public boolean isAutoBroadcastEnabled() {
        return enableAutoBroadcast;
    }

    /**
     * Enter the MsgHub context.
     * This method initializes subscriber relationships and broadcasts announcement messages.
     *
     * <p>Must be called before using the hub. Typically called in a try-with-resources block
     * or explicitly managed with enter()/exit().
     *
     * @return Mono containing this MsgHub instance
     */
    public Mono<MsgHub> enter() {
        return Mono.defer(
                () -> {
                    entered = true;
                    resetSubscribers();

                    // Broadcast announcement messages if present
                    if (announcement != null && !announcement.isEmpty()) {
                        return Flux.fromIterable(announcement)
                                .flatMap(this::broadcast)
                                .then(Mono.just(this));
                    }
                    return Mono.just(this);
                });
    }

    /**
     * Exit the MsgHub context.
     * This method cleans up subscriber relationships.
     *
     * <p>Called automatically when using try-with-resources via close().
     *
     * @return Mono that completes when cleanup is done
     */
    public Mono<Void> exit() {
        return Mono.defer(
                () -> {
                    if (enableAutoBroadcast) {
                        for (AgentBase agent : participants) {
                            agent.removeSubscribers(name);
                        }
                    }
                    entered = false;
                    return Mono.empty();
                });
    }

    /**
     * Close the MsgHub and cleanup resources.
     * This is the AutoCloseable implementation for try-with-resources support.
     */
    @Override
    public void close() {
        exit().block();
    }

    /**
     * Add new participants to this hub.
     * Automatically updates subscriber relationships if the hub is active.
     *
     * @param newParticipants Agents to add (varargs)
     * @return Mono that completes when participants are added
     */
    public Mono<Void> add(AgentBase... newParticipants) {
        return add(Arrays.asList(newParticipants));
    }

    /**
     * Add new participants to this hub.
     * Automatically updates subscriber relationships if the hub is active.
     *
     * @param newParticipants List of agents to add
     * @return Mono that completes when participants are added
     */
    public Mono<Void> add(List<AgentBase> newParticipants) {
        return Mono.defer(
                () -> {
                    for (AgentBase agent : newParticipants) {
                        if (!participants.contains(agent)) {
                            participants.add(agent);
                        }
                    }
                    if (entered) {
                        resetSubscribers();
                    }
                    return Mono.empty();
                });
    }

    /**
     * Remove participants from this hub.
     * Automatically updates subscriber relationships if the hub is active.
     *
     * @param toRemove Agents to remove (varargs)
     * @return Mono that completes when participants are removed
     */
    public Mono<Void> delete(AgentBase... toRemove) {
        return delete(Arrays.asList(toRemove));
    }

    /**
     * Remove participants from this hub.
     * Automatically updates subscriber relationships if the hub is active.
     *
     * @param toRemove List of agents to remove
     * @return Mono that completes when participants are removed
     */
    public Mono<Void> delete(List<AgentBase> toRemove) {
        return Mono.defer(
                () -> {
                    for (AgentBase agent : toRemove) {
                        if (participants.contains(agent)) {
                            participants.remove(agent);
                        } else {
                            log.warn(
                                    "Cannot find agent {} (id: {}), skip deletion",
                                    agent.getName(),
                                    agent.getAgentId());
                        }
                    }
                    if (entered) {
                        resetSubscribers();
                    }
                    return Mono.empty();
                });
    }

    /**
     * Broadcast a message to all participants.
     * All participants will receive the message via their observe() method.
     *
     * @param msg Message to broadcast
     * @return Mono that completes when all participants have observed the message
     */
    public Mono<Void> broadcast(Msg msg) {
        return Flux.fromIterable(participants).flatMap(agent -> agent.observe(msg)).then();
    }

    /**
     * Broadcast multiple messages to all participants.
     * All participants will receive all messages via their observe() method.
     *
     * @param msgs Messages to broadcast
     * @return Mono that completes when all participants have observed all messages
     */
    public Mono<Void> broadcast(List<Msg> msgs) {
        return Flux.fromIterable(msgs).flatMap(this::broadcast).then();
    }

    /**
     * Enable or disable automatic broadcasting.
     *
     * <p>When enabled, each participant's reply will be automatically broadcast to all other
     * participants. When disabled, the MsgHub only serves as a manual broadcaster.
     *
     * @param enable True to enable auto-broadcast, false to disable
     */
    public void setAutoBroadcast(boolean enable) {
        if (enable) {
            this.enableAutoBroadcast = true;
            if (entered) {
                resetSubscribers();
            }
        } else {
            this.enableAutoBroadcast = false;
            for (AgentBase agent : participants) {
                agent.removeSubscribers(name);
            }
        }
    }

    /**
     * Reset subscriber relationships for all participants.
     * Each participant will subscribe to all other participants (but not itself).
     */
    private void resetSubscribers() {
        if (enableAutoBroadcast) {
            for (AgentBase agent : participants) {
                List<AgentBase> others =
                        participants.stream()
                                .filter(a -> !a.equals(agent))
                                .collect(Collectors.toList());
                agent.resetSubscribers(name, others);
            }
        }
    }

    /**
     * Create a new MsgHub builder.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for MsgHub.
     */
    public static class Builder {
        private String name;
        private List<AgentBase> participants = new ArrayList<>();
        private List<Msg> announcement;
        private boolean enableAutoBroadcast = true;

        /**
         * Set the name of this MsgHub.
         * If not provided, a random UUID will be generated.
         *
         * @param name Hub name
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the participants for this MsgHub.
         *
         * @param participants Agents participating in this hub (varargs)
         * @return This builder
         */
        public Builder participants(AgentBase... participants) {
            this.participants = Arrays.asList(participants);
            return this;
        }

        /**
         * Set the participants for this MsgHub.
         *
         * @param participants List of agents participating in this hub
         * @return This builder
         */
        public Builder participants(List<AgentBase> participants) {
            this.participants = new ArrayList<>(participants);
            return this;
        }

        /**
         * Set announcement messages to broadcast when entering the hub.
         *
         * @param announcement Messages to broadcast on entry (varargs)
         * @return This builder
         */
        public Builder announcement(Msg... announcement) {
            this.announcement = Arrays.asList(announcement);
            return this;
        }

        /**
         * Set announcement messages to broadcast when entering the hub.
         *
         * @param announcement List of messages to broadcast on entry
         * @return This builder
         */
        public Builder announcement(List<Msg> announcement) {
            this.announcement = new ArrayList<>(announcement);
            return this;
        }

        /**
         * Enable or disable automatic broadcasting.
         * Default is true.
         *
         * @param enable True to enable auto-broadcast, false to disable
         * @return This builder
         */
        public Builder enableAutoBroadcast(boolean enable) {
            this.enableAutoBroadcast = enable;
            return this;
        }

        /**
         * Build the MsgHub instance.
         *
         * @return New MsgHub instance
         * @throws IllegalArgumentException if participants list is empty
         */
        public MsgHub build() {
            if (participants == null || participants.isEmpty()) {
                throw new IllegalArgumentException("MsgHub must have at least one participant");
            }
            return new MsgHub(this);
        }
    }
}
