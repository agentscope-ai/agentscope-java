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
package io.agentscope.harness.agent.memory.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all session tree entries (pi-mono-inspired JSONL session model).
 *
 * <p>Each entry has a unique {@code id} and a {@code parentId} forming a tree structure.
 * Entries are appended to the session JSONL file in order; they are never deleted.
 *
 * <p>Entry types:
 * <ul>
 *   <li>{@link MessageEntry} — wraps a single LLM message (user/assistant/tool/system)</li>
 *   <li>{@link CompactionEntry} — marks a compaction event (non-destructive)</li>
 *   <li>{@link SummaryEntry} — holds a compaction summary</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SessionEntry.MessageEntry.class, name = "message"),
    @JsonSubTypes.Type(value = SessionEntry.CompactionEntry.class, name = "compaction"),
    @JsonSubTypes.Type(value = SessionEntry.SummaryEntry.class, name = "summary")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract sealed class SessionEntry
        permits SessionEntry.MessageEntry, SessionEntry.CompactionEntry, SessionEntry.SummaryEntry {

    private final String id;
    private final String parentId;
    private final Instant timestamp;

    protected SessionEntry(String id, String parentId, Instant timestamp) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.parentId = parentId;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * A message entry wrapping a single message in the conversation.
     */
    public static final class MessageEntry extends SessionEntry {

        private final String role;
        private final String content;
        private final String toolCallId;

        @JsonCreator
        public MessageEntry(
                @JsonProperty("id") String id,
                @JsonProperty("parentId") String parentId,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("role") String role,
                @JsonProperty("content") String content,
                @JsonProperty("toolCallId") String toolCallId) {
            super(id, parentId, timestamp);
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
        }

        public MessageEntry(String parentId, String role, String content) {
            this(null, parentId, null, role, content, null);
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public String getToolCallId() {
            return toolCallId;
        }
    }

    /**
     * Non-destructive compaction marker. Records which entry is the first kept entry —
     * all entries before {@code firstKeptEntryId} are considered compacted (not visible
     * to the LLM) but remain in the file for full history replay.
     */
    public static final class CompactionEntry extends SessionEntry {

        private final String firstKeptEntryId;
        private final String summaryEntryId;

        @JsonCreator
        public CompactionEntry(
                @JsonProperty("id") String id,
                @JsonProperty("parentId") String parentId,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("firstKeptEntryId") String firstKeptEntryId,
                @JsonProperty("summaryEntryId") String summaryEntryId) {
            super(id, parentId, timestamp);
            this.firstKeptEntryId = firstKeptEntryId;
            this.summaryEntryId = summaryEntryId;
        }

        public String getFirstKeptEntryId() {
            return firstKeptEntryId;
        }

        public String getSummaryEntryId() {
            return summaryEntryId;
        }
    }

    /**
     * Holds the text of a compaction summary (the condensed version of compacted messages).
     */
    public static final class SummaryEntry extends SessionEntry {

        private final String summary;
        private final String format;

        @JsonCreator
        public SummaryEntry(
                @JsonProperty("id") String id,
                @JsonProperty("parentId") String parentId,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("summary") String summary,
                @JsonProperty("format") String format) {
            super(id, parentId, timestamp);
            this.summary = summary;
            this.format = format != null ? format : "structured";
        }

        public SummaryEntry(String parentId, String summary) {
            this(null, parentId, null, summary, "structured");
        }

        public String getSummary() {
            return summary;
        }

        public String getFormat() {
            return format;
        }
    }
}
