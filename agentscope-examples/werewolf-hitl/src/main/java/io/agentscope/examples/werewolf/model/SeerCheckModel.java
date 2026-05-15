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
package io.agentscope.examples.werewolf.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Structured output model for seer's identity check. */
public class SeerCheckModel {
    @JsonProperty("查验的目标")
    public String targetPlayer;

    @JsonProperty("查验目标的原因")
    public String reason;

    public SeerCheckModel() {}
}
