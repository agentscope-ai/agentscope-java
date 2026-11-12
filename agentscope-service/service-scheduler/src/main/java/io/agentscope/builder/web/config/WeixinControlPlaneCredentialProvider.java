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
package io.agentscope.builder.web.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.weixin.WeixinCredentialProvider;
import io.agentscope.extensions.channel.weixin.WeixinCredentials;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/** Resolves a managed Weixin credential from the control plane on demand. */
final class WeixinControlPlaneCredentialProvider implements WeixinCredentialProvider {
    private static final Logger log =
            LoggerFactory.getLogger(WeixinControlPlaneCredentialProvider.class);

    private final WebClient controlPlane;
    private final ObjectMapper objectMapper;
    private final String channelId;
    private final long revision;

    WeixinControlPlaneCredentialProvider(
            WebClient controlPlane, ObjectMapper objectMapper, String channelId, long revision) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        if (revision <= 0) throw new IllegalArgumentException("credential revision required");
        this.revision = revision;
    }

    @Override
    public WeixinCredentials current() {
        try {
            byte[] body =
                    controlPlane
                            .post()
                            .uri(
                                    builder ->
                                            builder.pathSegment(
                                                            "api",
                                                            "internal",
                                                            "channels",
                                                            channelId,
                                                            "credential")
                                                    .build())
                            .bodyValue(Map.of("revision", revision))
                            .retrieve()
                            .bodyToMono(byte[].class)
                            .block(Duration.ofSeconds(15));
            JsonNode node = objectMapper.readTree(body == null ? new byte[0] : body);
            String token = node == null ? null : node.path("botToken").asText(null);
            if (token == null || token.isBlank()) {
                log.warn(
                        "Weixin credential response missing botToken for channel '{}':"
                                + " bodyBytes={}, isObject={}, fields={}",
                        channelId,
                        body == null ? 0 : body.length,
                        node != null && node.isObject(),
                        node == null ? "<null>" : node.fieldNames().hasNext());
                throw new IllegalStateException("control plane returned no Weixin credential");
            }
            return new WeixinCredentials(token);
        } catch (Exception ex) {
            // JSON parser diagnostics can include the input, which contains the token.
            log.warn(
                    "Weixin credential lookup failed for channel '{}': {}",
                    channelId,
                    ex.getClass().getSimpleName());
            throw new IllegalStateException("Weixin credential unavailable");
        }
    }
}
