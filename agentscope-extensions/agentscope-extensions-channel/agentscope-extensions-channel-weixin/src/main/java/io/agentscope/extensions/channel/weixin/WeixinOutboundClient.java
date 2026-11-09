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
                                if (msg.getTextContent() == null
                                        || msg.getTextContent().isBlank()) {
                                    continue;
                                }
                                sendOne(address, msg, context, beforeSend);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sends one message and returns the provider's receipt for it. A host that persists replies
     * needs the receipt, not a local acknowledgement: only the provider's message id proves iLink
     * accepted the reply.
     */
    Mono<String> sendWithReceipt(
            OutboundAddress address, Msg message, String context, Runnable beforeSend) {
        return Mono.fromCallable(() -> sendOne(address, message, context, beforeSend))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String sendOne(
            OutboundAddress address, Msg message, String context, Runnable beforeSend) {
        String text = message.getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Weixin reply is empty");
        }
        String token =
                context != null
                        ? context
                        : (message.getMetadata() == null
                                ? null
                                : (String) message.getMetadata().get("weixinContextToken"));
        try {
            Map<String, Object> item = Map.of("type", 1, "text_item", Map.of("text", text));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from_user_id", "");
            m.put("to_user_id", peer(address));
            m.put("client_id", UUID.randomUUID().toString());
            m.put("message_type", 2);
            m.put("message_state", 2);
            m.put("item_list", List.of(item));
            if (token != null) m.put("context_token", token);
            // iLink reports an accepted send by returning the message id alone: there is no
            // ret/errcode in a success body.
            com.fasterxml.jackson.databind.JsonNode response =
                    assertSuccess(
                            "sendmessage",
                            post(
                                    "/ilink/bot/sendmessage",
                                    Map.of("msg", m, "base_info", baseInfo()),
                                    beforeSend),
                            "message_id");
            String receipt = response.path("message_id").asText("");
            if (receipt.isBlank()) {
                throw new WeixinOperationException("iLink sendmessage returned no receipt");
            }
            return receipt;
        } catch (WeixinCredentialRejectedException | WeixinOperationException e) {
            // Provider outcome and credential failures keep their type: wrapping them hides the
            // reason from the caller's onError.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Weixin send failed", e);
        }
    }

    /** Grace on top of the long-poll window before the request is abandoned. */
    private static final long LONG_POLL_GRACE_MS = 5_000L;

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
        requireProviderResult("getupdates", response, "msgs", "get_updates_buf");
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
                                        .timeout(Duration.ofMillis(timeoutFor(path))),
                                token())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                        .build();
        beforeSend.run();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new WeixinOperationException("iLink HTTP " + r.statusCode());
        return r.body();
    }

    /**
     * {@code getupdates} holds the connection open for the provider's long-poll window, so its read
     * timeout has to outlast {@code longPollTimeoutMs}; every control call keeps
     * {@code requestTimeoutMs}.
     */
    private long timeoutFor(String path) {
        return path.endsWith("getupdates")
                ? Math.max((long) p.longPollTimeoutMs() + LONG_POLL_GRACE_MS, p.requestTimeoutMs())
                : p.requestTimeoutMs();
    }

    private String token() {
        return credentials.current().botToken();
    }

    private Map<String, String> baseInfo() {
        return Map.of("channel_version", p.channelVersion(), "bot_agent", p.botAgent());
    }

    /**
     * Validates a provider outcome and returns the parsed body. The failure text carries only the
     * numeric provider codes: the provider's own {@code errmsg} is never copied into an exception,
     * because that text reaches host logs and may embed anything the provider chose to echo.
     */
    private static com.fasterxml.jackson.databind.JsonNode assertSuccess(
            String operation, String body, String... outcomeFields) throws Exception {
        com.fasterxml.jackson.databind.JsonNode response = parse(body);
        requireProviderResult(operation, response, outcomeFields);
        int ret = response.path("ret").asInt(0);
        int errcode = response.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
            if (ret == -14 || errcode == -14) {
                throw new WeixinCredentialRejectedException(operation, -14);
            }
            throw new WeixinOperationException(
                    "iLink " + operation + " failed ret=" + ret + " errcode=" + errcode);
        }
        return response;
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
            throw new WeixinOperationException(
                    "iLink returned an unparseable response"
                            + (where == null
                                    ? ""
                                    : " near line "
                                            + where.getLineNr()
                                            + ", column "
                                            + where.getColumnNr()));
        }
    }

    /**
     * A provider response has to state its outcome. Defaulting a missing {@code ret} to zero would
     * accept any 2xx body — an empty object, a proxy error page rendered as JSON — as a delivered
     * message, and the channel would then complete the inbox claim and drop the reply.
     *
     * <p>{@code getupdates} is the one call that reports success without {@code ret}/{@code
     * errcode}: an idle long poll answers with its batch fields only ({@code msgs}, {@code
     * get_updates_buf}) and stays silent about a result code. Those fields are its outcome, so a
     * poll body carrying neither the status fields nor one of {@code outcomeFields} is still
     * rejected — the relaxation must not turn an unrelated 2xx body into an empty batch.
     */
    private static void requireProviderResult(
            String operation,
            com.fasterxml.jackson.databind.JsonNode response,
            String... outcomeFields) {
        if (response.has("ret") || response.has("errcode")) {
            return;
        }
        for (String field : outcomeFields) {
            if (response.has(field)) {
                return;
            }
        }
        throw new WeixinOperationException(
                "iLink " + operation + " response carries no ret/errcode");
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
