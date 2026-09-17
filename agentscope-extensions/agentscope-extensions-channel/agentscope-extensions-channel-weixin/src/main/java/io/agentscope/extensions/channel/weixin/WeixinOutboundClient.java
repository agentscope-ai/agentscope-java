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
package io.agentscope.extensions.channel.weixin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Minimal iLink JSON client for text send and update polling. */
public final class WeixinOutboundClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final WeixinChannelProperties p;
    private final WeixinCredentialProvider credentials;

    public WeixinOutboundClient(WeixinChannelProperties p, WeixinCredentialProvider credentials) {
        this.p = java.util.Objects.requireNonNull(p, "properties");
        this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(p.requestTimeoutMs()))
                        .build();
    }

    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        return sendWithContext(address, messages, null);
    }

    public Mono<Void> sendWithContext(OutboundAddress address, List<Msg> messages, String context) {
        return sendWithContext(address, messages, context, () -> {});
    }

    Mono<Void> sendWithContext(
            OutboundAddress address, List<Msg> messages, String context, Runnable beforeSend) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            for (Msg msg : messages) {
                                String text = msg.getTextContent();
                                if (text == null || text.isBlank()) continue;
                                String token =
                                        context != null
                                                ? context
                                                : (msg.getMetadata() == null
                                                        ? null
                                                        : (String)
                                                                msg.getMetadata()
                                                                        .get("weixinContextToken"));
                                try {
                                    Map<String, Object> item =
                                            Map.of("type", 1, "text_item", Map.of("text", text));
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("from_user_id", "");
                                    m.put("to_user_id", peer(address));
                                    m.put("client_id", UUID.randomUUID().toString());
                                    m.put("message_type", 2);
                                    m.put("message_state", 2);
                                    m.put("item_list", List.of(item));
                                    if (token != null) m.put("context_token", token);
                                    assertSuccess(
                                            "sendmessage",
                                            post(
                                                    "/ilink/bot/sendmessage",
                                                    Map.of("msg", m, "base_info", baseInfo()),
                                                    beforeSend));
                                } catch (WeixinCredentialRejectedException e) {
                                    throw e;
                                } catch (Exception e) {
                                    throw new RuntimeException("Weixin send failed", e);
                                }
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public JsonNodeResponse updates(String cursor) throws Exception {
        String body =
                post(
                        "/ilink/bot/getupdates",
                        Map.of(
                                "get_updates_buf",
                                cursor == null ? "" : cursor,
                                "base_info",
                                baseInfo()));
        com.fasterxml.jackson.databind.JsonNode response = parse(body);
        List<com.fasterxml.jackson.databind.JsonNode> messages = new ArrayList<>();
        response.path("msgs").forEach(messages::add);
        return new JsonNodeResponse(
                response.path("ret").asInt(0),
                response.has("errcode") ? response.path("errcode").asInt() : null,
                response.path("errmsg").asText(null),
                messages,
                response.path("get_updates_buf").asText(null),
                response.has("longpolling_timeout_ms")
                        ? response.path("longpolling_timeout_ms").asInt()
                        : null);
    }

    public void notifyStart() throws Exception {
        notifyStart(() -> {});
    }

    void notifyStart(Runnable beforeSend) throws Exception {
        assertSuccess(
                "notifystart",
                post("/ilink/bot/msg/notifystart", Map.of("base_info", baseInfo()), beforeSend));
    }

    public void notifyStop() throws Exception {
        notifyStop(() -> {});
    }

    void notifyStop(Runnable beforeSend) throws Exception {
        assertSuccess(
                "notifystop",
                post("/ilink/bot/msg/notifystop", Map.of("base_info", baseInfo()), beforeSend));
    }

    private String post(String path, Object payload) throws Exception {
        return post(path, payload, () -> {});
    }

    private String post(String path, Object payload, Runnable beforeSend) throws Exception {
        HttpRequest req =
                WeixinProtocolHeaders.authenticatedJsonPost(
                                HttpRequest.newBuilder(
                                                URI.create(p.baseUrl().replaceAll("/$", "") + path))
                                        .timeout(Duration.ofMillis(p.requestTimeoutMs())),
                                token())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                        .build();
        beforeSend.run();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new IllegalStateException("iLink HTTP " + r.statusCode());
        return r.body();
    }

    private String token() {
        return credentials.current().botToken();
    }

    private Map<String, String> baseInfo() {
        return Map.of("channel_version", p.channelVersion(), "bot_agent", p.botAgent());
    }

    private static void assertSuccess(String operation, String body) throws Exception {
        com.fasterxml.jackson.databind.JsonNode response = parse(body);
        int ret = response.path("ret").asInt(0);
        int errcode = response.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
            if (ret == -14 || errcode == -14) {
                throw new WeixinCredentialRejectedException(operation, -14);
            }
            throw new IllegalStateException(
                    "iLink send failed ret="
                            + ret
                            + " errcode="
                            + errcode
                            + " "
                            + response.path("errmsg").asText(""));
        }
    }

    /**
     * Parses a provider body without letting Jackson quote it: inbox payloads and context tokens
     * travel in these responses, and the default parse error embeds a fragment of the input.
     */
    private static com.fasterxml.jackson.databind.JsonNode parse(String body) throws Exception {
        try {
            return JSON.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            com.fasterxml.jackson.core.JsonLocation where = error.getLocation();
            throw new IllegalStateException(
                    "iLink returned an unparseable response"
                            + (where == null
                                    ? ""
                                    : " near line "
                                            + where.getLineNr()
                                            + ", column "
                                            + where.getColumnNr()));
        }
    }

    private static String peer(OutboundAddress a) {
        String s = a.to();
        int i = s.lastIndexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    public record JsonNodeResponse(
            int ret,
            Integer errcode,
            String errmsg,
            List<com.fasterxml.jackson.databind.JsonNode> msgs,
            String get_updates_buf,
            Integer longpolling_timeout_ms) {}
}
