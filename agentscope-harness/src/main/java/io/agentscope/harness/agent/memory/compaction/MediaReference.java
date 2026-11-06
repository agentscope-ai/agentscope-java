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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * A safe, non-recoverable reference to a media block used in compaction summaries and telemetry.
 *
 * <p>Instances never carry the raw binary data, URL, access token or file path. They only describe
 * the media kind, MIME type, an opaque stable identifier, an optional hash and an optional size so
 * the summarization model still knows that media existed in the conversation history.
 */
public final class MediaReference {

    /** Media modality. */
    public enum Kind {
        IMAGE,
        AUDIO,
        VIDEO,
        DATA
    }

    /** Relationship between the reference and any persistent recovery contract. */
    public enum Disposition {
        /** Summarized inline; no claim that the original bytes can be recovered. */
        DESCRIBED,
        /** Only metadata is available; the original payload is not locally recoverable. */
        REFERENCE_ONLY,
        /** Offloaded to a persistent attachment store and recoverable by ID. */
        OFFLOADED
    }

    private final Kind kind;
    private final String id;
    private final String mimeType;
    private final String name;
    private final long sizeBytes;
    private final String sha256;
    private final Disposition disposition;
    private final String protocolCategory;

    private MediaReference(
            Kind kind,
            String id,
            String mimeType,
            String name,
            long sizeBytes,
            String sha256,
            Disposition disposition,
            String protocolCategory) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.mimeType = mimeType;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.disposition = Objects.requireNonNull(disposition, "disposition cannot be null");
        this.protocolCategory = protocolCategory;
    }

    /** Builds a reference for an {@link ImageBlock}. */
    public static MediaReference fromImageBlock(
            ImageBlock block, String messageId, int ordinal, Disposition disposition) {
        Source source = block.getSource();
        String id = stableId(messageId, ordinal, null);
        return new MediaReference(
                Kind.IMAGE,
                id,
                mimeType(source),
                null,
                sizeBytes(source),
                hash(source),
                disposition,
                protocolCategory(source));
    }

    /** Builds a reference for an {@link AudioBlock}. */
    public static MediaReference fromAudioBlock(
            AudioBlock block, String messageId, int ordinal, Disposition disposition) {
        Source source = block.getSource();
        String id = stableId(messageId, ordinal, null);
        return new MediaReference(
                Kind.AUDIO,
                id,
                mimeType(source),
                null,
                sizeBytes(source),
                hash(source),
                disposition,
                protocolCategory(source));
    }

    /** Builds a reference for a {@link VideoBlock}. */
    public static MediaReference fromVideoBlock(
            VideoBlock block, String messageId, int ordinal, Disposition disposition) {
        Source source = block.getSource();
        String id = stableId(messageId, ordinal, null);
        return new MediaReference(
                Kind.VIDEO,
                id,
                mimeType(source),
                null,
                sizeBytes(source),
                hash(source),
                disposition,
                protocolCategory(source));
    }

    /** Builds a reference for a {@link DataBlock}. */
    public static MediaReference fromDataBlock(DataBlock block, Disposition disposition) {
        Source source = block.getSource();
        String id = block.getId();
        if (id == null || id.isBlank()) {
            String sourceHash = hash(source);
            id = sourceHash != null ? "data-" + sourceHash : "data-unknown";
        }
        return new MediaReference(
                Kind.DATA,
                id,
                mimeType(source),
                block.getName(),
                sizeBytes(source),
                hash(source),
                disposition,
                protocolCategory(source));
    }

    /** Returns a pure-text placeholder suitable for a summarization prompt. */
    public String toPlaceholder() {
        StringBuilder sb = new StringBuilder("[attachment");
        sb.append(" id=").append(escape(id));
        if (mimeType != null && !mimeType.isBlank()) {
            sb.append(" type=").append(escape(mimeType));
        }
        if (name != null && !name.isBlank()) {
            sb.append(" name=").append(escape(name));
        }
        sb.append(" disposition=").append(disposition.name().toLowerCase(Locale.ROOT));
        if (sha256 != null && !sha256.isBlank()) {
            sb.append(" sha256=").append(sha256);
        }
        if (sizeBytes >= 0) {
            sb.append(" bytes=").append(sizeBytes);
        }
        if (protocolCategory != null && !protocolCategory.isBlank()) {
            sb.append(" protocol=").append(escape(protocolCategory));
        }
        sb.append(" kind=").append(kind.name().toLowerCase(Locale.ROOT));
        sb.append("]");
        return sb.toString();
    }

    public Kind getKind() {
        return kind;
    }

    public String getId() {
        return id;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getName() {
        return name;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Disposition getDisposition() {
        return disposition;
    }

    public String getProtocolCategory() {
        return protocolCategory;
    }

    private static String stableId(String messageId, int ordinal, String dataBlockId) {
        if (dataBlockId != null && !dataBlockId.isBlank()) {
            return dataBlockId;
        }
        String base = messageId == null ? "msg" : messageId;
        return base + "#" + ordinal;
    }

    private static String mimeType(Source source) {
        if (source instanceof Base64Source b64) {
            return b64.getMediaType();
        }
        if (source instanceof URLSource url) {
            return url.getMimeType();
        }
        return null;
    }

    private static long sizeBytes(Source source) {
        if (source instanceof Base64Source b64) {
            // Approximate decoded byte count without materializing the whole decoded array.
            String data = b64.getData();
            return data.length() * 3L / 4L;
        }
        return -1;
    }

    private static String hash(Source source) {
        if (source instanceof Base64Source b64) {
            return sha256Hex(b64.getData());
        }
        if (source instanceof URLSource url) {
            return sha256Hex(url.getUrl());
        }
        return null;
    }

    private static String protocolCategory(Source source) {
        if (!(source instanceof URLSource url)) {
            return null;
        }
        String lower = url.getUrl().toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return "https";
        }
        if (lower.startsWith("http://")) {
            return "http";
        }
        if (lower.startsWith("file://")) {
            return "file";
        }
        int colon = lower.indexOf(':');
        return colon > 0 ? lower.substring(0, colon) : "unknown";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String escaped =
                value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("[", "\\[")
                        .replace("]", "\\]")
                        .replace("=", "\\=")
                        .replace(" ", "\\ ");
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == 0x7F) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
