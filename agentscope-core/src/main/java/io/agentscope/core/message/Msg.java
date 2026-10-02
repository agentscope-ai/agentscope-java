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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.util.TypeUtils;
import java.beans.Transient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Represents a message in the AgentScope framework.
 *
 * <p>Messages are the primary communication unit between agents, users, and tools.
 * Each message has a role (user, assistant, system, or tool), content blocks,
 * and optional metadata.
 *
 * <p>Content blocks can include text, images, audio, video, thinking content,
 * tool use blocks, and tool result blocks. The content is stored as an immutable
 * list for thread safety.
 *
 * <p>Messages are serialized to JSON using Jackson and include a unique ID
 * for tracking purposes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        visible = true,
        defaultImpl = Msg.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "USER"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "ASSISTANT"),
    @JsonSubTypes.Type(value = SystemMessage.class, name = "SYSTEM"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "TOOL"),
})
public class Msg implements State {

    /** Metadata key for storing the generate reason. */
    public static final String METADATA_GENERATE_REASON = "agentscope_generate_reason";

    /**
     * Metadata key for carrying a {@code List<ConfirmResult>} when resuming a Permission HITL
     * pause. The receiving {@code ReActAgent.call(msgs)} extracts and applies these results to
     * the ASKING tool calls in context.
     */
    public static final String METADATA_CONFIRM_RESULTS = "agentscope_confirm_results";

    /**
     * Metadata key (boolean) marking a message as <em>synthetic</em>: framework-injected rather
     * than authored by the user, the model, or a tool. Synthetic messages (e.g. the per-turn todo
     * reminder produced by {@code TaskReminderMiddleware}) are appended transiently to the
     * reasoning input and are not persisted into {@code AgentState.context}; this flag lets any
     * downstream consumer that does observe such a message recognise and skip it (memory
     * extraction, compaction, RAG recall).
     */
    public static final String METADATA_SYNTHETIC = "agentscope_synthetic";

    /**
     * Metadata key (string) describing the kind of synthetic reminder carried by a message, e.g.
     * {@code "todo_state"}. Only meaningful when {@link #METADATA_SYNTHETIC} is {@code true}.
     */
    public static final String METADATA_REMINDER_KIND = "agentscope_reminder_kind";

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final String id;

    private final String name;

    private final MsgRole role;

    private final List<ContentBlock> content;

    private final Map<String, Object> metadata;

    private final String timestamp;

    private final ChatUsage usage;

    /**
     * Creates a new message with the specified fields.
     *
     * @param id Unique identifier for the message
     * @param name Optional name for the message (can be null)
     * @param role The role of the message sender (user, assistant, system, or tool)
     * @param content List of content blocks that make up the message content
     * @param metadata Optional metadata map for additional information
     * @param timestamp Optional timestamp string (if null, will be generated automatically)
     */
    @JsonCreator
    protected Msg(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") MsgRole role,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content =
                Objects.nonNull(content)
                        ? content.stream().filter(Objects::nonNull).toList()
                        : List.of();
        this.metadata = new HashMap<>();
        if (Objects.nonNull(metadata)) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (Objects.isNull(key) || Objects.isNull(value)) {
                    continue;
                }
                this.metadata.put(key, value);
            }
        }
        this.timestamp = timestamp;
        this.usage = usage;
        validateRoleContent(this.role, this.content);
    }

    /**
     * Validates that the content blocks are compatible with the message role.
     *
     * <p>The legacy {@link ImageBlock}/{@link AudioBlock}/{@link VideoBlock} types
     * remain valid on {@link MsgRole#USER} alongside the unified {@link DataBlock}.
     * {@link MsgRole#TOOL} is legacy and treated as unrestricted (same as assistant)
     * to preserve back-compat.
     *
     * @param role The message role
     * @param content The content blocks
     * @throws IllegalArgumentException if any block is not allowed for the role
     */
    private static void validateRoleContent(MsgRole role, List<ContentBlock> content) {
        if (role == null || content == null || content.isEmpty()) {
            return;
        }
        switch (role) {
            case USER -> {
                for (ContentBlock block : content) {
                    if (!(block instanceof TextBlock
                            || block instanceof DataBlock
                            || block instanceof ImageBlock
                            || block instanceof AudioBlock
                            || block instanceof VideoBlock)) {
                        throw new IllegalArgumentException(
                                "USER message may only contain text/data/image/audio/video blocks,"
                                        + " got "
                                        + block.getClass().getSimpleName());
                    }
                }
            }
            case SYSTEM -> {
                for (ContentBlock block : content) {
                    if (!(block instanceof TextBlock)) {
                        throw new IllegalArgumentException(
                                "SYSTEM message may only contain text blocks, got "
                                        + block.getClass().getSimpleName());
                    }
                }
            }
            case ASSISTANT, TOOL -> {
                // No restriction; TOOL preserved for back-compat.
            }
        }
    }

    /**
     * Creates a new message builder with a randomly generated ID.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder for the concrete {@link Msg} subtype that matches the given role.
     *
     * <p>Unlike {@link #builder()} (which produces a plain {@link Msg}), this factory returns
     * the role-specific builder so the resulting instance is the appropriate subtype:
     * {@link UserMessage} for {@link MsgRole#USER}, {@link AssistantMessage} for
     * {@link MsgRole#ASSISTANT}, {@link SystemMessage} for {@link MsgRole#SYSTEM}, and
     * {@link ToolResultMessage} for {@link MsgRole#TOOL}. A {@code null} role falls back to the
     * base {@link #builder()}.
     *
     * <p>The returned builder's {@code build()} is overridden covariantly, so the declared
     * {@link Builder} static type still yields the concrete subtype instance at runtime.
     *
     * @param role the role whose concrete message subtype should be built
     * @return a role-specific builder, or the base builder when {@code role} is {@code null}
     */
    public static Builder builderForRole(MsgRole role) {
        if (role == null) {
            return builder();
        }
        return switch (role) {
            case USER -> UserMessage.builder();
            case ASSISTANT -> AssistantMessage.builder();
            case SYSTEM -> SystemMessage.builder();
            case TOOL -> ToolResultMessage.builder();
        };
    }

    /**
     * Generates a random UUID string for use as a message ID.
     * Exposed to subclasses so their convenience constructors can mirror the
     * behaviour of {@link Builder} without re-implementing UUID logic.
     */
    protected static String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the current wall-clock timestamp in this class's canonical format.
     * Exposed to subclasses so their convenience constructors can stamp messages
     * identically to {@link Builder}.
     */
    protected static String currentTimestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.now());
    }

    /**
     * Gets the unique identifier of this message.
     *
     * @return The message ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the optional name of this message.
     *
     * @return The message name, or null if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the role of the message sender.
     *
     * @return The message role (user, assistant, system, or tool)
     */
    public MsgRole getRole() {
        return role;
    }

    /**
     * Gets the immutable list of content blocks in this message.
     *
     * @return The content blocks list, may be empty but never null
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Gets the metadata associated with this message.
     *
     * @return The metadata map, or null if not set
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets the timestamp of this message.
     *
     * @return The timestamp string in format "yyyy-MM-dd HH:mm:ss.SSS"
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the token usage information associated with this message.
     *
     * <p>Aligns with Python {@code Msg.usage} (PR #1639). When populated,
     * contains the input/output token counts from the model call that
     * produced this message.
     *
     * @return The usage object, or null if not available
     */
    public ChatUsage getUsage() {
        return usage;
    }

    /**
     * Check if this message has content blocks of the specified type (type-safe).
     *
     * @param blockClass Block class to check for
     * @param <T> Content block type
     * @return true if at least one block of the type exists
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> boolean hasContentBlocks(Class<T> blockClass) {
        return content.stream().anyMatch(blockClass::isInstance);
    }

    /**
     * Get all content blocks of the specified type (type-safe).
     *
     * @param blockClass Block class to filter for
     * @param <T> Content block type
     * @return List of matching blocks
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> List<T> getContentBlocks(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, blockClass))
                .collect(Collectors.toList());
    }

    /**
     * Get the first content block, or null if empty.
     *
     * @return First content block or null
     */
    @Transient
    @JsonIgnore
    public ContentBlock getFirstContentBlock() {
        return content.isEmpty() ? null : content.get(0);
    }

    /**
     * Get the first content block of the specified type (type-safe).
     *
     * @param blockClass Block class to search for
     * @param <T> Content block type
     * @return First matching block or null
     */
    @Transient
    @JsonIgnore
    public <T extends ContentBlock> T getFirstContentBlock(Class<T> blockClass) {
        return content.stream()
                .filter(blockClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, blockClass))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if this message contains structured data in metadata.
     *
     * @return true if metadata is present and non-empty
     */
    @Transient
    @JsonIgnore
    public boolean hasStructuredData() {
        return metadata != null && metadata.containsKey(MessageMetadataKeys.STRUCTURED_OUTPUT);
    }

    /**
     * Extract structured data from message metadata and convert it to the specified type.
     *
     * <p>This method is useful when the message contains structured input from a user agent
     * or structured output from an LLM. The metadata map is converted to a Java object
     * using Jackson's ObjectMapper.
     *
     * <p>Example usage:
     * <pre>{@code
     * public class TaskPlan {
     *     public String goal;
     *     public int priority;
     * }
     *
     * Msg msg = userAgent.call(null, TaskPlan.class).block();
     * TaskPlan plan = msg.getStructuredData(TaskPlan.class);
     * }</pre>
     *
     * @param targetClass The class to convert metadata into
     * @param <T> Type of the structured data
     * @return The structured data object
     * @throws IllegalStateException if no metadata exists
     * @throws IllegalArgumentException if conversion fails
     */
    @Transient
    @JsonIgnore
    public <T> T getStructuredData(Class<T> targetClass) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        Object structuredOutput = metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        if (structuredOutput == null) {
            throw new IllegalStateException(
                    "No structured output in message metadata. Key '"
                            + MessageMetadataKeys.STRUCTURED_OUTPUT
                            + "' not found.");
        }
        try {
            return JsonUtils.getJsonCodec().convertValue(structuredOutput, targetClass);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to convert metadata to "
                            + targetClass.getSimpleName()
                            + ". Ensure the target class has appropriate fields matching metadata"
                            + " keys.",
                    e);
        }
    }

    /**
     * Extract structured data from message metadata and convert it to the java.util.Map.
     *
     * <p>This method is useful when the message contains structured input from a user agent
     * or structured output from an LLM. support for using dynamic schema processing
     *
     * <p>Example usage:
     * <pre>{@code
     * String json = """
     *         {
     *                 						 "type": "object",
     *                 						 "properties": {
     *                 						   "productName": {
     *                 							 "type": "string"
     *                 						                                              },
     *                 						   "features": {
     *                 							 "type": "array",
     *                 							 "items": {
     *                 							   "type": "string"                                             *                                           }
     *                 						   },
     *                 						   "pricing": {
     *                 							 "type": "object",
     *                 							 "properties": {
     *                 							   "amount": {
     *                                                  e": "number"
     *                 							   },
     *                 							   "currency": {
     *                                                  e": "string"
     *                                             }
     *                                           }
     *                 						   },
     *                 						   "ratings": {
     *                 							 "type": "object",
     *                 							 "additionalProperties": {
     *                                                   e": "integer"
     *                                           }
     *                                         }
     *                                       }
     *                 					   }
     *         """;
     *  JsonNode sampleJsonNode = new ObjectMapper().readTree(json);
     *   Msg msg = agent.call(input, sampleJsonNode).block(TEST_TIMEOUT);
     *   Map<String, Object> structuredData = msg.getStructuredData(false);
     * }</pre>
     *
     * @return The copied metadata
     * @throws IllegalStateException if no metadata exists
     */
    @Transient
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStructuredData(boolean mutable) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        Object structuredOutput = metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT);
        if (structuredOutput == null) {
            throw new IllegalStateException(
                    "No structured output in message metadata. Key '"
                            + MessageMetadataKeys.STRUCTURED_OUTPUT
                            + "' not found.");
        }
        if (!(structuredOutput instanceof Map)) {
            throw new IllegalStateException(
                    "Structured output is not a Map. Use getStructuredData(Class<T>) instead.");
        }
        Map<String, Object> result = (Map<String, Object>) structuredOutput;
        if (mutable) {
            return result;
        }
        try {
            return JsonUtils.getJsonCodec()
                    .convertValue(result, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert structured output to Map.", e);
        }
    }

    /**
     * Extracts plain text content from this message.
     *
     * <p>This method concatenates all text blocks in the message, joined by newlines.
     * If the message contains no text blocks, an empty string is returned.
     *
     * @return The concatenated text content from all text blocks, or empty string if none
     */
    @Transient
    @JsonIgnore
    public String getTextContent() {
        return content.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Gets the chat usage statistics from this message's metadata.
     *
     * <p>This method retrieves the accumulated token usage information that was
     * recorded during model generation. Returns null if no usage information
     * is available.
     *
     * <p>Example usage:
     * <pre>{@code
     * Msg response = agent.call(userMsg).block();
     * ChatUsage usage = response.getChatUsage();
     * if (usage != null) {
     *     System.out.println("Input tokens: " + usage.getInputTokens());
     *     System.out.println("Output tokens: " + usage.getOutputTokens());
     *     System.out.println("Total tokens: " + usage.getTotalTokens());
     *     System.out.println("Time: " + usage.getTime() + "s");
     * }
     * }</pre>
     *
     * @return The ChatUsage object containing token counts and timing, or null if not available
     */
    @Transient
    @JsonIgnore
    public ChatUsage getChatUsage() {
        if (this.usage != null) {
            return this.usage;
        }
        if (metadata == null) {
            return null;
        }
        Object metaUsage = metadata.get(MessageMetadataKeys.CHAT_USAGE);
        if (metaUsage instanceof ChatUsage) {
            return (ChatUsage) metaUsage;
        }
        if (metaUsage instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) metaUsage;
            ChatUsage chatUsage =
                    ChatUsage.builder()
                            .inputTokens(toInt(map.get("inputTokens")))
                            .outputTokens(toInt(map.get("outputTokens")))
                            .time(toDouble(map.get("time")))
                            .build();
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
            return chatUsage;
        }
        return null;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * Gets the reason why this message was generated.
     *
     * <p>This method helps users understand the context of agent execution:
     * <ul>
     *   <li>{@link GenerateReason#MODEL_STOP} - Task completed normally</li>
     *   <li>{@link GenerateReason#TOOL_SUSPENDED} - External tools need user execution</li>
     *   <li>{@link GenerateReason#REASONING_STOP_REQUESTED} - HITL stop in reasoning phase</li>
     *   <li>{@link GenerateReason#ACTING_STOP_REQUESTED} - HITL stop in acting phase</li>
     *   <li>{@link GenerateReason#INTERRUPTED} - Agent was interrupted</li>
     *   <li>{@link GenerateReason#MAX_ITERATIONS} - Maximum iterations reached</li>
     * </ul>
     *
     * @return The generate reason, defaults to {@link GenerateReason#MODEL_STOP} if not set
     */
    @Transient
    @JsonIgnore
    public GenerateReason getGenerateReason() {
        if (metadata == null) {
            return GenerateReason.MODEL_STOP;
        }
        Object reason = metadata.get(METADATA_GENERATE_REASON);
        if (reason instanceof String s) {
            try {
                return GenerateReason.valueOf(s);
            } catch (IllegalArgumentException e) {
                return GenerateReason.MODEL_STOP;
            }
        }
        if (reason instanceof GenerateReason gr) {
            return gr;
        }
        return GenerateReason.MODEL_STOP;
    }

    /**
     * Creates a new message with the specified generate reason.
     *
     * <p>This method returns a new Msg instance with the updated generate reason
     * stored in metadata. The original message is not modified (immutable).
     *
     * @param reason The generate reason to set
     * @return A new Msg instance with the updated generate reason
     */
    public Msg withGenerateReason(GenerateReason reason) {
        Map<String, Object> newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(METADATA_GENERATE_REASON, reason.name());
        return new Msg(
                this.id,
                this.name,
                this.role,
                this.content,
                newMetadata,
                this.timestamp,
                this.usage);
    }

    /**
     * Returns a copy of this message with the given content blocks.
     *
     * @param newContent the replacement content blocks
     * @return a new Msg with identical metadata but replaced content
     */
    public Msg withContent(List<ContentBlock> newContent) {
        return new Msg(
                this.id,
                this.name,
                this.role,
                newContent,
                this.metadata,
                this.timestamp,
                this.usage);
    }

    public static class Builder {

        protected String id;

        protected String name;

        protected MsgRole role = MsgRole.USER;

        protected List<ContentBlock> content = List.of();

        protected Map<String, Object> metadata = Map.of();

        protected String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());

        protected ChatUsage usage;

        /**
         * Creates a new builder with a randomly generated message ID.
         */
        public Builder() {
            randomId();
        }

        /**
         * Sets the unique identifier for the message.
         *
         * @param id The message ID
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Generates a random UUID for the message ID.
         */
        private void randomId() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * Sets the optional name for the message.
         *
         * @param name The message name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the role for the message.
         *
         * @param role The message role (user, assistant, system, or tool)
         * @return This builder for chaining
         */
        public Builder role(MsgRole role) {
            this.role = role;
            return this;
        }

        /**
         * Set content from a list of content blocks.
         * @param content List of content blocks
         * @return This builder
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * Set content from a single content block (convenience method).
         * The block will be wrapped in a list automatically.
         * @param block Single content block
         * @return This builder
         */
        public Builder content(ContentBlock block) {
            this.content = block == null ? List.of() : List.of(block);
            return this;
        }

        /**
         * Set content from varargs content blocks (convenience method).
         * @param blocks Content blocks
         * @return This builder
         */
        public Builder content(ContentBlock... blocks) {
            this.content = blocks == null ? List.of() : List.of(blocks);
            return this;
        }

        /**
         * Set text content from a string.
         * @param text Text content
         * @return This builder
         */
        public Builder textContent(String text) {
            this.content = List.of(TextBlock.builder().text(text).build());
            return this;
        }

        /**
         * Set metadata for structured output.
         * @param metadata Metadata map
         * @return This builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : metadata;
            return this;
        }

        /**
         * Sets the timestamp for the message.
         *
         * @param timestamp The timestamp string
         * @return This builder for chaining
         */
        public Builder timestamp(String timestamp) {
            this.timestamp =
                    timestamp == null ? TIMESTAMP_FORMATTER.format(Instant.now()) : timestamp;
            return this;
        }

        /**
         * Sets the generate reason for this message.
         *
         * <p>The generate reason indicates why this message was generated by the agent,
         * helping users understand the execution context and required follow-up actions.
         *
         * @param reason The generate reason
         * @return This builder for chaining
         */
        /**
         * Sets the token usage information for this message.
         *
         * @param usage The ChatUsage containing token counts
         * @return This builder for chaining
         */
        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder generateReason(GenerateReason reason) {
            if (reason != null) {
                if (this.metadata == null || this.metadata.isEmpty()) {
                    this.metadata = new HashMap<>();
                } else if (!(this.metadata instanceof HashMap)) {
                    this.metadata = new HashMap<>(this.metadata);
                }
                this.metadata.put(METADATA_GENERATE_REASON, reason.name());
            }
            return this;
        }

        /**
         * Builds a new message instance with the configured properties.
         * If timestamp is not set, it will be auto-generated.
         *
         * @return A new immutable message
         */
        public Msg build() {
            return new Msg(id, name, role, content, metadata, timestamp, usage);
        }
    }
}
