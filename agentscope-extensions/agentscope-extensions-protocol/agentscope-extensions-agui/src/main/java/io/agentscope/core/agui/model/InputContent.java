/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing a typed content part in the AG-UI protocol.
 *
 * <p>This corresponds to the AG-UI protocol's {@code InputContent} union type,
 * covering text, image, audio, video, and document inputs.
 *
 * <p>JSON polymorphism is handled via the {@code type} discriminator field,
 * consistent with the AG-UI protocol specification:
 * <ul>
 *   <li>{@code "text"} &rarr; {@link TextInputContent}</li>
 *   <li>{@code "image"} &rarr; {@link ImageInputContent}</li>
 *   <li>{@code "audio"} &rarr; {@link AudioInputContent}</li>
 *   <li>{@code "video"} &rarr; {@link VideoInputContent}</li>
 *   <li>{@code "document"} &rarr; {@link DocumentInputContent}</li>
 * </ul>
 *
 * @see MessageContent
 * @see io.agentscope.core.agui.converter.AguiMessageConverter
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextInputContent.class, name = "text"),
    @JsonSubTypes.Type(value = ImageInputContent.class, name = "image"),
    @JsonSubTypes.Type(value = AudioInputContent.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoInputContent.class, name = "video"),
    @JsonSubTypes.Type(value = DocumentInputContent.class, name = "document")
})
public sealed interface InputContent
        permits TextInputContent,
                ImageInputContent,
                AudioInputContent,
                VideoInputContent,
                DocumentInputContent {}
