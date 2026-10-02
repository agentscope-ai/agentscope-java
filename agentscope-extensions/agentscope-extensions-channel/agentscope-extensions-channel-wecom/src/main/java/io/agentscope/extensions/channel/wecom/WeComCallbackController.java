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
package io.agentscope.extensions.channel.wecom;

import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.io.StringReader;
import java.util.Objects;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import reactor.core.publisher.Mono;

/**
 * Handles the WeCom callback URL handshake (GET) and inbound encrypted message delivery (POST).
 *
 * <p>Each {@link WeComChannel} registers itself with the controller at start-up; the controller
 * dispatches incoming requests by {@code channelId} extracted from the URL path. This avoids
 * Spring-side dynamic mapping registration and keeps wiring trivially testable.
 */
@RestController
@RequestMapping("/api/channels/wecom")
public class WeComCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeComCallbackController.class);
    private static final DocumentBuilderFactory DBF = newSafeDocumentBuilderFactory();

    private final WeComChannelRegistry registry;

    public WeComCallbackController() {
        this(WeComChannelRegistry.instance());
    }

    /** Visible for tests — allows injecting a fresh registry. */
    WeComCallbackController(WeComChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * URL verification handshake. WeCom calls this with an encrypted {@code echostr} that the
     * server must decrypt and return verbatim so WeCom can confirm the server holds the right
     * encoding AES key.
     */
    @GetMapping(value = "/{channelId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @org.springframework.web.bind.annotation.PathVariable String channelId,
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        WeComChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("WeCom verify: no channel registered for id='{}'", channelId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        WeComCrypto crypto = channel.crypto();
        if (!crypto.verifySignature(signature, timestamp, nonce, echostr)) {
            log.warn("WeCom verify: signature mismatch for channelId='{}'", channelId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String plain = crypto.decrypt(echostr);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(plain);
        } catch (RuntimeException e) {
            log.warn(
                    "WeCom verify: decrypt failed for channelId='{}': {}",
                    channelId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * Inbound encrypted message. The request body is XML containing {@code <Encrypt>...</Encrypt>}.
     * Verifies signature, decrypts, deduplicates by {@code MsgId}, applies bot-loop guard, then
     * dispatches to the channel for routing. Always returns {@code 200} so WeCom does not retry,
     * unless the signature is invalid or the channel is unknown.
     */
    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> dispatch(
            @org.springframework.web.bind.annotation.PathVariable String channelId,
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {
        WeComChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("WeCom dispatch: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        String encrypt = extractEncrypt(body);
        if (encrypt == null) {
            log.warn("WeCom dispatch: missing <Encrypt> in body (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.badRequest().body(""));
        }
        if (!channel.crypto().verifySignature(signature, timestamp, nonce, encrypt)) {
            log.warn("WeCom dispatch: signature mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        String xml;
        try {
            xml = channel.crypto().decrypt(encrypt);
        } catch (RuntimeException e) {
            log.warn(
                    "WeCom dispatch: decrypt failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // Idempotency: drop duplicate MsgId silently with 200 so WeCom stops retrying.
        Optional<String> msgId = WeComInboundMapper.extractMsgId(xml);
        if (msgId.isPresent() && !channel.idempotency().firstSeen(channelId + "|" + msgId.get())) {
            log.debug(
                    "WeCom dispatch: duplicate msgId={} (channelId='{}')", msgId.get(), channelId);
            return Mono.just(ResponseEntity.ok(""));
        }

        Optional<InboundMessage> inbound = channel.mapper().map(xml);
        if (inbound.isEmpty()) {
            // Non-text payload or no content; ack so WeCom stops retrying.
            return Mono.just(ResponseEntity.ok(""));
        }
        InboundMessage in = inbound.get();
        // Bot-loop guard on the conversation peer key.
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "WeCom dispatch: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok(""));
        }

        // Dispatch on the channel; the channel handles the reply outbound delivery.
        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "WeCom dispatch: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            // WeCom must still get a 2xx to avoid retry storms.
                            return Mono.just(ResponseEntity.ok(""));
                        });
    }

    private static String extractEncrypt(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            NodeList list = root.getElementsByTagName("Encrypt");
            if (list == null || list.getLength() == 0) {
                return null;
            }
            Node node = list.item(0);
            String v = node != null ? node.getTextContent() : null;
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // Best-effort hardening.
        }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }
}
