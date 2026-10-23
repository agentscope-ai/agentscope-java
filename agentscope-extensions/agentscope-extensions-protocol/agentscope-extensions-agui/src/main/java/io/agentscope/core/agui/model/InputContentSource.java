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
 * Sealed interface representing a content source in the AG-UI protocol.
 *
 * <p>Corresponds to the AG-UI protocol's {@code InputContentSource} union type:
 * <ul>
 *   <li>{@code "data"} &rarr; {@link InputContentDataSource} (inline Base64 data)</li>
 *   <li>{@code "url"} &rarr; {@link InputContentUrlSource} (remote URL reference)</li>
 * </ul>
 *
 * <p>This is distinct from AgentScope's internal {@link io.agentscope.core.message.Source}
 * hierarchy ({@code URLSource} / {@code Base64Source}). The {@code AguiMessageConverter}
 * translates between the two layers.
 *
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InputContentDataSource.class, name = "data"),
    @JsonSubTypes.Type(value = InputContentUrlSource.class, name = "url")
})
public sealed interface InputContentSource permits InputContentDataSource, InputContentUrlSource {}
