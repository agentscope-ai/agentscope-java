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
 * Base interface for multimodal content parts in the AG-UI protocol.
 *
 * <p>The AG-UI protocol supports multimodal messages where the {@code content} field
 * can be an array of typed content parts. Each part specifies a {@code type} discriminator
 * that determines the concrete content type.
 *
 * @see AguiTextContent
 * @see AguiImageContent
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AguiTextContent.class, name = "text"),
    @JsonSubTypes.Type(value = AguiImageContent.class, name = "image")
})
public sealed interface AguiContentPart permits AguiTextContent, AguiImageContent {}
