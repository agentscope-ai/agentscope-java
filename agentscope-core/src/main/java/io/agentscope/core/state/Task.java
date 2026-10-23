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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent agent task record carried inside {@link TaskContextState}.
 *
 * <p>Tasks are user-visible work items. Their {@link #state} progresses {@code pending → in_progress
 * → completed}; dependencies are declared via {@link #blocks} (downstream task ids waiting on this
 * one) and {@link #blockedBy} (upstream task ids whose completion gates this one).
 *
 * <p>All fields except {@code subject} and {@code description} have sensible defaults so callers
 * normally invoke {@link Builder#subject(String)} + {@link Builder#description(String)} and let the
 * other fields populate themselves.
 */
public final class Task {

    /** Allowed values for {@link #state}. */
    public enum State {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static State fromWire(String wire) {
            if (wire == null) {
                return null;
            }
            for (State s : values()) {
                if (s.wire.equalsIgnoreCase(wire)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown task state: " + wire);
        }
    }

    private final String subject;
    private final String description;
    private final Map<String, Object> metadata;
    private final String createdAt;
    private final State state;
    private final String id;
    private final String owner;
    private final List<String> blocks;
    private final List<String> blockedBy;

    private Task(Builder builder) {
        this.subject = Objects.requireNonNull(builder.subject, "subject must not be null");
        this.description =
                Objects.requireNonNull(builder.description, "description must not be null");
        this.metadata =
                builder.metadata == null
                        ? Map.of()
                        : Map.copyOf(new LinkedHashMap<>(builder.metadata));
        this.createdAt = builder.createdAt == null ? defaultCreatedAt() : builder.createdAt;
        this.state = builder.state == null ? State.PENDING : builder.state;
        this.id = builder.id == null ? UUID.randomUUID().toString().replace("-", "") : builder.id;
        this.owner = builder.owner;
        this.blocks = builder.blocks == null ? List.of() : List.copyOf(builder.blocks);
        this.blockedBy = builder.blockedBy == null ? List.of() : List.copyOf(builder.blockedBy);
    }

    @JsonCreator
    static Task fromJson(
            @JsonProperty("subject") String subject,
            @JsonProperty("description") String description,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("state") State state,
            @JsonProperty("id") String id,
            @JsonProperty("owner") String owner,
            @JsonProperty("blocks") List<String> blocks,
            @JsonProperty("blocked_by") List<String> blockedBy) {
        Builder b =
                builder()
                        .subject(subject)
                        .description(description)
                        .metadata(metadata)
                        .createdAt(createdAt)
                        .state(state)
                        .id(id)
                        .owner(owner);
        if (blocks != null) {
            b.blocks(blocks);
        }
        if (blockedBy != null) {
            b.blockedBy(blockedBy);
        }
        return b.build();
    }

    private static String defaultCreatedAt() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @JsonProperty("subject")
    public String getSubject() {
        return subject;
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    @JsonProperty("metadata")
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @JsonProperty("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    @JsonProperty("state")
    public State getState() {
        return state;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("owner")
    public String getOwner() {
        return owner;
    }

    @JsonProperty("blocks")
    public List<String> getBlocks() {
        return blocks;
    }

    @JsonProperty("blocked_by")
    public List<String> getBlockedBy() {
        return blockedBy;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Task other)) {
            return false;
        }
        return Objects.equals(id, other.id)
                && Objects.equals(subject, other.subject)
                && Objects.equals(description, other.description)
                && Objects.equals(metadata, other.metadata)
                && Objects.equals(createdAt, other.createdAt)
                && state == other.state
                && Objects.equals(owner, other.owner)
                && Objects.equals(blocks, other.blocks)
                && Objects.equals(blockedBy, other.blockedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id, subject, description, metadata, createdAt, state, owner, blocks, blockedBy);
    }

    @Override
    public String toString() {
        return "Task{id="
                + id
                + ", subject="
                + subject
                + ", state="
                + state
                + ", owner="
                + owner
                + '}';
    }

    public static final class Builder {
        private String subject;
        private String description;
        private Map<String, Object> metadata;
        private String createdAt;
        private State state;
        private String id;
        private String owner;
        private List<String> blocks;
        private List<String> blockedBy;

        private Builder() {}

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        public Builder blocks(List<String> blocks) {
            this.blocks = blocks == null ? null : new ArrayList<>(blocks);
            return this;
        }

        public Builder blockedBy(List<String> blockedBy) {
            this.blockedBy = blockedBy == null ? null : new ArrayList<>(blockedBy);
            return this;
        }

        public Task build() {
            return new Task(this);
        }
    }
}
