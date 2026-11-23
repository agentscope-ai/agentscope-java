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

package io.agentscope.durable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** Public content only. Native reasoning, tool bookkeeping and checkpoints stay in the adapter. */
public final class DurableMessages {
    private DurableMessages() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Text.class, name = "text"),
        @JsonSubTypes.Type(value = Image.class, name = "image"),
        @JsonSubTypes.Type(value = Audio.class, name = "audio"),
        @JsonSubTypes.Type(value = Video.class, name = "video")
    })
    public sealed interface Content permits Text, Image, Audio, Video {}

    public record Text(String text) implements Content {}

    public record Image(Source source) implements Content {}

    public record Audio(Source source) implements Content {}

    public record Video(Source source) implements Content {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Url.class, name = "url"),
        @JsonSubTypes.Type(value = Base64.class, name = "base64")
    })
    public sealed interface Source permits Url, Base64 {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Url(String url, String mimeType) implements Source {}

    public record Base64(String mimeType, String data) implements Source {}

    public record Input(String schemaVersion, List<Content> content) {
        public Input {
            content = List.copyOf(content);
        }

        public static Input text(String text) {
            return new Input("1", List.of(new Text(text)));
        }
    }

    public record Message(
            String schemaVersion, String messageId, String role, List<Content> content) {
        public Message {
            content = List.copyOf(content);
        }
    }

    /** Host-owned artifact, published via artifact.published; the core does not fetch its URI. */
    public record Artifact(
            String threadId,
            String turnId,
            String runId,
            String stepId,
            String artifactId,
            String uri,
            String mimeType,
            String name,
            Long size,
            String digest) {}
}
