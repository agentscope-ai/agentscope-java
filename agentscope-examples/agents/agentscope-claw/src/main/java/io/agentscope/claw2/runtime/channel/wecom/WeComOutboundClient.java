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
package io.agentscope.claw2.runtime.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.channel.OutboundAddress;
import io.agentscope.claw2.runtime.channel.PeerKind;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * WeCom outbound HTTP client. Wraps {@code /cgi-bin/message/send} (DM) and
 * {@code /cgi-bin/appchat/send} (app group chat). The first {@link Msg#getTextContent() text
 * content} of each {@link Msg} is sent.
 *
 * <p>For text content that contains markdown control characters we send {@code msgtype=markdown};
 * otherwise plain text. (Heuristic only — callers that explicitly want markdown should already use
 * markdown syntax in their content.)
 */
public final class WeComOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WeComOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final WeComAccessTokenProvider tokenProvider;
    private final int agentId;

    public WeComOutboundClient(
            String apiBase, WeComAccessTokenProvider tokenProvider, int agentId) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.tokenProvider = tokenProvider;
        this.agentId = agentId;
    }

    /**
     * Sends each {@code msg} to the resolved peer. The address's {@code to} field has the form
     * {@code "channelId:peerKey"} where {@code peerKey} is {@code "DIRECT:userid"} or
     * {@code "GROUP:chatid"}.
     */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        PeerTarget target = parseAddress(address);
        return Mono.from(
                Mono.defer(() -> tokenProvider.token())
                        .flatMapMany(
                                token ->
                                        reactor.core.publisher.Flux.fromIterable(messages)
                                                .concatMap(msg -> sendOne(token, target, msg)))
                        .then());
    }

    private Mono<Void> sendOne(String token, PeerTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        boolean useMarkdown = looksLikeMarkdown(text);
        switch (target.kind()) {
            case DIRECT -> {
                body.put("touser", target.id());
                body.put("agentid", agentId);
                body.put("msgtype", useMarkdown ? "markdown" : "text");
                body.put(
                        useMarkdown ? "markdown" : "text",
                        Collections.singletonMap("content", text));
                return post("/cgi-bin/message/send", token, body);
            }
            case GROUP -> {
                body.put("chatid", target.id());
                body.put("msgtype", useMarkdown ? "markdown" : "text");
                body.put(
                        useMarkdown ? "markdown" : "text",
                        Collections.singletonMap("content", text));
                return post("/cgi-bin/appchat/send", token, body);
            }
            default -> {
                return Mono.error(
                        new IllegalArgumentException(
                                "WeCom outbound only supports DIRECT and GROUP peer kinds,"
                                        + " got "
                                        + target.kind()));
            }
        }
    }

    private Mono<Void> post(String path, String token, Map<String, Object> body) {
        return client.post()
                .uri(uri -> uri.path(path).queryParam("access_token", token).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .doOnNext(this::checkSendResponse)
                .then();
    }

    private void checkSendResponse(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode == 42001 || errcode == 40014) {
                tokenProvider.invalidate();
            }
            if (errcode != 0) {
                log.warn(
                        "WeCom send returned errcode={}, errmsg={}",
                        errcode,
                        node.path("errmsg").asText());
            }
        } catch (Exception e) {
            log.warn("Failed to parse WeCom send response: {}", e.getMessage());
        }
    }

    private static PeerTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int sep = to.indexOf(':');
        if (sep < 0) {
            return new PeerTarget(PeerKind.DIRECT, to);
        }
        String rest = to.substring(sep + 1);
        int sep2 = rest.indexOf(':');
        String kindRaw;
        String id;
        if (sep2 < 0) {
            kindRaw = "DIRECT";
            id = rest;
        } else {
            kindRaw = rest.substring(0, sep2);
            id = rest.substring(sep2 + 1);
        }
        PeerKind kind;
        try {
            kind = PeerKind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            kind = PeerKind.DIRECT;
        }
        return new PeerTarget(kind, id);
    }

    private static boolean looksLikeMarkdown(String text) {
        return text.contains("\n")
                || text.contains("**")
                || text.contains("```")
                || text.contains("[");
    }

    private record PeerTarget(PeerKind kind, String id) {}
}
