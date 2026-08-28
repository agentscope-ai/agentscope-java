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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure transformations between inline base64 image messages and lightweight payload references.
 *
 * <p>The transformer does not read or write storage. Callers persist the returned payload map and
 * lightweight messages in the order required by their storage consistency model.
 */
public final class ImagePayloadTransformer {

    private static final String REFERENCE_NAMESPACE = "__agentscope_image_ref__:";

    /** Versioned prefix reserved for AgentScope image payload references. */
    public static final String REFERENCE_PREFIX = REFERENCE_NAMESPACE + "v1:";

    private static final byte[] HASH_DOMAIN =
            "agentscope-image-payload-v1".getBytes(StandardCharsets.UTF_8);

    private static final Pattern REFERENCE_PATTERN =
            Pattern.compile(Pattern.quote(REFERENCE_PREFIX) + "([0-9a-f]{64})");

    private ImagePayloadTransformer() {}

    /**
     * Replaces inline base64 images with content-addressed references.
     *
     * <p>Legacy {@link ImageBlock} values and image MIME {@link DataBlock} values are transformed,
     * including values nested in {@link ToolResultBlock#getOutput()}. URL sources and non-image
     * media remain unchanged.
     */
    public static OffloadResult offload(List<Msg> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        Map<String, ImagePayloadState> payloads = new LinkedHashMap<>();
        List<Msg> transformed = transformMessages(messages, payloads, null, false);
        return new OffloadResult(transformed, payloads);
    }

    /**
     * Restores referenced image payloads into a detached message list.
     *
     * @throws IllegalArgumentException if a reserved reference is malformed
     * @throws IllegalStateException if a referenced payload is absent or fails integrity checks
     */
    public static List<Msg> hydrate(List<Msg> messages, Map<String, ImagePayloadState> payloads) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(payloads, "payloads must not be null");
        return transformMessages(messages, null, payloads, true);
    }

    /**
     * Returns the payload identifiers referenced by the supported image blocks, in encounter order.
     *
     * <p>Inline base64 values are ignored. Malformed values in the reserved reference namespace are
     * rejected using the same rules as {@link #offload(List)} and {@link #hydrate(List, Map)}.
     */
    public static Set<String> referencedPayloadIds(List<Msg> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        Set<String> result = new LinkedHashSet<>();
        for (Msg message : messages) {
            Objects.requireNonNull(message, "messages must not contain null elements");
            collectReferencedPayloadIds(message.getContent(), result);
        }
        return Collections.unmodifiableSet(result);
    }

    private static void collectReferencedPayloadIds(
            List<ContentBlock> blocks, Set<String> referencedPayloadIds) {
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock image) {
                collectReferencedPayloadId(image.getSource(), referencedPayloadIds);
            } else if (block instanceof DataBlock data && isImageSource(data.getSource())) {
                collectReferencedPayloadId(data.getSource(), referencedPayloadIds);
            } else if (block instanceof ToolResultBlock toolResult) {
                collectReferencedPayloadIds(toolResult.getOutput(), referencedPayloadIds);
            }
        }
    }

    private static void collectReferencedPayloadId(
            Source source, Set<String> referencedPayloadIds) {
        if (source instanceof Base64Source base64) {
            String payloadId = parseReference(base64.getData());
            if (payloadId != null) {
                referencedPayloadIds.add(payloadId);
            }
        }
    }

    private static List<Msg> transformMessages(
            List<Msg> messages,
            Map<String, ImagePayloadState> collectedPayloads,
            Map<String, ImagePayloadState> availablePayloads,
            boolean hydrate) {
        List<Msg> result = new ArrayList<>(messages.size());
        for (Msg message : messages) {
            Objects.requireNonNull(message, "messages must not contain null elements");
            List<ContentBlock> content =
                    transformBlocks(
                            message.getContent(), collectedPayloads, availablePayloads, hydrate);
            result.add(content == message.getContent() ? message : copyMessage(message, content));
        }
        return List.copyOf(result);
    }

    private static List<ContentBlock> transformBlocks(
            List<ContentBlock> blocks,
            Map<String, ImagePayloadState> collectedPayloads,
            Map<String, ImagePayloadState> availablePayloads,
            boolean hydrate) {
        List<ContentBlock> result = null;
        for (int i = 0; i < blocks.size(); i++) {
            ContentBlock original = blocks.get(i);
            ContentBlock transformed =
                    transformBlock(original, collectedPayloads, availablePayloads, hydrate);
            if (result != null) {
                result.add(transformed);
            } else if (transformed != original) {
                result = new ArrayList<>(blocks.size());
                result.addAll(blocks.subList(0, i));
                result.add(transformed);
            }
        }
        return result == null ? blocks : List.copyOf(result);
    }

    private static ContentBlock transformBlock(
            ContentBlock block,
            Map<String, ImagePayloadState> collectedPayloads,
            Map<String, ImagePayloadState> availablePayloads,
            boolean hydrate) {
        if (block instanceof ImageBlock image) {
            Source source =
                    transformSource(
                            image.getSource(), collectedPayloads, availablePayloads, hydrate);
            if (source == image.getSource()) {
                return image;
            }
            return ImageBlock.builder()
                    .source(source)
                    .minPixels(image.getMinPixels())
                    .maxPixels(image.getMaxPixels())
                    .build();
        }
        if (block instanceof DataBlock data && isImageSource(data.getSource())) {
            Source source =
                    transformSource(
                            data.getSource(), collectedPayloads, availablePayloads, hydrate);
            if (source == data.getSource()) {
                return data;
            }
            return DataBlock.builder().source(source).id(data.getId()).name(data.getName()).build();
        }
        if (block instanceof ToolResultBlock toolResult) {
            List<ContentBlock> output =
                    transformBlocks(
                            toolResult.getOutput(), collectedPayloads, availablePayloads, hydrate);
            if (output == toolResult.getOutput()) {
                return toolResult;
            }
            return new ToolResultBlock(
                    toolResult.getId(),
                    toolResult.getName(),
                    output,
                    toolResult.getMetadata(),
                    toolResult.getState());
        }
        return block;
    }

    private static Source transformSource(
            Source source,
            Map<String, ImagePayloadState> collectedPayloads,
            Map<String, ImagePayloadState> availablePayloads,
            boolean hydrate) {
        if (!(source instanceof Base64Source base64)) {
            return source;
        }
        if (hydrate) {
            return hydrateSource(base64, availablePayloads);
        }
        return offloadSource(base64, collectedPayloads);
    }

    private static Source offloadSource(
            Base64Source source, Map<String, ImagePayloadState> payloads) {
        String existingReference = parseReference(source.getData());
        if (existingReference != null) {
            return source;
        }
        ImagePayloadState payload = new ImagePayloadState(source.getMediaType(), source.getData());
        String payloadId = payloadId(payload);
        ImagePayloadState previous = payloads.putIfAbsent(payloadId, payload);
        if (previous != null && !previous.equals(payload)) {
            throw new IllegalStateException("SHA-256 collision for image payload: " + payloadId);
        }
        return new Base64Source(source.getMediaType(), REFERENCE_PREFIX + payloadId);
    }

    private static Source hydrateSource(
            Base64Source source, Map<String, ImagePayloadState> payloads) {
        String payloadId = parseReference(source.getData());
        if (payloadId == null) {
            return source;
        }
        ImagePayloadState payload = payloads.get(payloadId);
        if (payload == null) {
            throw new IllegalStateException("Missing image payload: " + payloadId);
        }
        if (!source.getMediaType().equals(payload.mediaType())) {
            throw new IllegalStateException("Image payload media type mismatch: " + payloadId);
        }
        if (!payloadId.equals(payloadId(payload))) {
            throw new IllegalStateException("Image payload hash mismatch: " + payloadId);
        }
        return new Base64Source(payload.mediaType(), payload.data());
    }

    private static boolean isImageSource(Source source) {
        return source instanceof Base64Source base64
                && base64.getMediaType().regionMatches(true, 0, "image/", 0, "image/".length());
    }

    private static String parseReference(String data) {
        if (!data.startsWith(REFERENCE_NAMESPACE)) {
            return null;
        }
        Matcher matcher = REFERENCE_PATTERN.matcher(data);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed image payload reference: " + data);
        }
        return matcher.group(1);
    }

    private static String payloadId(ImagePayloadState payload) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        updateLengthPrefixed(digest, HASH_DOMAIN);
        updateLengthPrefixed(digest, payload.mediaType().getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, payload.data().getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest();
        return java.util.HexFormat.of().formatHex(hash);
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static Msg copyMessage(Msg message, List<ContentBlock> content) {
        Msg.Builder builder;
        if (message.getClass() == Msg.class) {
            builder = Msg.builder().role(message.getRole());
        } else if (message.getClass() == UserMessage.class
                || message.getClass() == AssistantMessage.class
                || message.getClass() == SystemMessage.class
                || message.getClass() == ToolResultMessage.class) {
            builder = Msg.builderForRole(message.getRole());
        } else {
            return message.copyWithContent(content);
        }
        builder.id = message.getId();
        builder.name = message.getName();
        builder.content = content;
        builder.metadata = message.getMetadata();
        builder.timestamp = message.getTimestamp();
        builder.usage = message.getUsage();
        return builder.build();
    }

    /** Result of an offload transformation; payloads are keyed by lowercase SHA-256. */
    public record OffloadResult(List<Msg> messages, Map<String, ImagePayloadState> payloads) {

        public OffloadResult {
            Objects.requireNonNull(messages, "messages must not be null");
            Objects.requireNonNull(payloads, "payloads must not be null");
            messages = List.copyOf(messages);
            payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
        }
    }
}
