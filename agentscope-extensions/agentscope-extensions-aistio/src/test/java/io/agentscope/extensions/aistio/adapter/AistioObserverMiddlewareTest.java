/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import org.junit.jupiter.api.Test;

class AistioObserverMiddlewareTest {

    @Test
    void toolMetadataPreservesCallIdentityAndFailureState() throws Exception {
        JsonNode metadata =
                ControlPlaneHttpClient.mapper()
                        .readTree(AistioObserverMiddleware.toolMetadata("call-42", "error"));

        assertEquals("call-42", metadata.path("toolCallId").asText());
        assertEquals("error", metadata.path("state").asText());
    }
}
