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
package io.agentscope.extensions.channel.common;

import java.util.Map;
import java.util.Objects;

/**
 * Metadata key contract for inbound media (image / audio / video / file) messages surfaced by
 * channel inbound mappers.
 *
 * <p>Channel adapters deliberately do not decode provider media into content blocks: material
 * identifiers ({@code media_id} / {@code image_key} / {@code file_key} / {@code downloadCode})
 * are not URLs and can only be exchanged for bytes through a token-authenticated download whose
 * artifacts are short-lived. Deciding whether and when to fetch, where to buffer, and how to
 * degrade is application policy. Mappers therefore surface a media event as a user {@code Msg}
 * whose text content is a neutral {@link #markerText(String, String) marker} and whose metadata
 * carries the keys defined here, so the application can observe the event, download the material
 * through its own path, or reply with guidance.
 *
 * <p>All values are strings. Keys absent on a given platform are omitted; readers must treat the
 * metadata as a sparse map and never assume a key's presence.
 */
public final class ChannelMediaMetadata {

    /** Maximum length, in code points, of the file-name copy embedded in {@link
     * #markerText(String, String)}. */
    private static final int MARKER_FILE_NAME_MAX_LENGTH = 80;

    /** Normalized media kind: image (photo / picture). */
    public static final String KIND_IMAGE = "image";

    /** Normalized media kind: audio (voice message). */
    public static final String KIND_AUDIO = "audio";

    /** Normalized media kind: video, including video-bearing composite messages. */
    public static final String KIND_VIDEO = "video";

    /** Normalized media kind: generic file attachment. */
    public static final String KIND_FILE = "file";

    /** Normalized media kind — one of {@code image}, {@code audio}, {@code video}, {@code file}. */
    public static final String KIND = "channelMediaKind";

    /** Original provider type token, verbatim (WeCom {@code MsgType}, Feishu {@code
     * message_type}, DingTalk {@code msgtype}) — e.g. {@code voice}, {@code picture}, {@code
     * media}. */
    public static final String PROVIDER_TYPE = "channelMediaProviderType";

    /** Primary provider material identifier ({@code MediaId} / {@code image_key} / {@code
     * file_key} / {@code downloadCode}), verbatim. */
    public static final String ID = "channelMediaId";

    /** Secondary material identifier when the platform provides one (thumbnail / cover image /
     * picture-specific download code). */
    public static final String SECONDARY_ID = "channelMediaSecondaryId";

    /** Directly fetchable URL when the platform provides one alongside the material id (WeCom
     * image {@code PicUrl}). */
    public static final String URL = "channelMediaUrl";

    /** File name, when the platform reports one. */
    public static final String FILE_NAME = "channelMediaFileName";

    /** Container / format token (WeCom voice {@code Format}, DingTalk video {@code videoType}). */
    public static final String FORMAT = "channelMediaFormat";

    /** Playback duration in milliseconds, as reported by the platform (Feishu and DingTalk
     * both document the field in milliseconds for audio and video messages). */
    public static final String DURATION_MS = "channelMediaDurationMs";

    /** Platform speech-to-text result for audio messages, when the platform provides one. */
    public static final String RECOGNITION = "channelMediaRecognition";

    private ChannelMediaMetadata() {}

    /**
     * Returns the neutral marker text used as the {@code Msg} text content of a mapped media
     * message: {@code "[image]"}, {@code "[audio]"}, {@code "[video]"}, or {@code
     * "[file: report.pdf]"} when a file name is available. The marker keeps the event visible to
     * text-only agents; applications are free to rewrite the text before dispatching.
     *
     * <p>File names are provider-controlled input, so the embedded copy is sanitized — brackets,
     * control characters and invisible formatting characters are removed, whitespace is
     * collapsed and the copy is truncated — keeping the marker inert against crafted names; the
     * raw name is preserved unmodified under {@link #FILE_NAME}.
     *
     * @param kind normalized media kind, as in {@link #KIND}
     * @param fileName optional file name; a sanitized copy is embedded when non-blank
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public static String markerText(String kind, String fileName) {
        Objects.requireNonNull(kind, "kind cannot be null");
        if (fileName == null || fileName.isBlank()) {
            return "[" + kind + "]";
        }
        String safe = sanitizeMarkerFileName(fileName);
        if (safe.isEmpty()) {
            return "[" + kind + "]";
        }
        return "[" + kind + ": " + safe + "]";
    }

    /**
     * Returns a marker-safe copy of a provider-reported file name: brackets, control characters,
     * invisible formatting characters (zero-width spaces, bidirectional overrides) and Unicode
     * line/paragraph separators are replaced with spaces, so a {@code ]} cannot close the marker
     * early, a line break cannot start a new line of agent-visible text, and an invisible
     * character cannot make the copy render as something other than what it contains. Whitespace
     * runs collapse to one space, and the copy is truncated to at most {@code
     * MARKER_FILE_NAME_MAX_LENGTH} code points without splitting surrogate pairs. Only this copy
     * is altered — the raw name travels separately under {@link #FILE_NAME}.
     */
    private static String sanitizeMarkerFileName(String fileName) {
        String safe =
                fileName.replaceAll("[\\[\\]\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ")
                        .replaceAll("\\s+", " ")
                        .strip();
        if (safe.codePointCount(0, safe.length()) <= MARKER_FILE_NAME_MAX_LENGTH) {
            return safe;
        }
        int limit = safe.offsetByCodePoints(0, MARKER_FILE_NAME_MAX_LENGTH);
        return safe.substring(0, limit).strip();
    }

    /**
     * Puts {@code value} under {@code key} only when {@code value} is neither {@code null} nor
     * blank; a null/blank value is silently skipped. Channel payloads omit platform-optional
     * fields; this keeps the metadata map sparse.
     *
     * @throws NullPointerException if {@code metadata} or {@code key} is {@code null}
     */
    public static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
