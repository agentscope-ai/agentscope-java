/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.agentscope.core.a2a.agent.hitl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A typed answer to one tool in an {@link A2aHandoff}. */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "responseType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = A2aUserConfirmation.class, name = "user-confirmation"),
    @JsonSubTypes.Type(value = A2aExternalToolResponse.class, name = "external-tool-response")
})
public sealed interface A2aHitlResponse permits A2aUserConfirmation, A2aExternalToolResponse {

    String toolCallId();

    @JsonProperty("responseType")
    String responseType();
}
