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
package io.agentscope.claw2.runtime.channel.gitlab;

import io.agentscope.claw2.runtime.channel.OutboundAddress;
import io.agentscope.core.message.Msg;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * GitLab outbound HTTP client. Posts a note via
 * {@code POST /api/v4/projects/{id}/{issues|merge_requests}/{iid}/notes} where {@code id} is the
 * URL-encoded {@code path_with_namespace}.
 *
 * <p>{@link OutboundAddress#to()} format:
 * {@code "<channelId>:thread:<path_with_namespace>#<iid>:<Issue|MergeRequest>"}.
 */
public final class GitLabOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabOutboundClient.class);

    private final WebClient client;
    private final String token;

    public GitLabOutboundClient(String apiBase, String token) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.token = token;
    }

    /** Sends each message as a separate note on the resolved thread. */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        NoteTarget target = parseAddress(address);
        if (target == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "GitLab outbound: unparseable peerId '" + address.to() + "'"));
        }
        return reactor.core.publisher.Flux.fromIterable(messages)
                .concatMap(msg -> sendOne(target, msg))
                .then();
    }

    private Mono<Void> sendOne(NoteTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        String encodedPath = URLEncoder.encode(target.path, StandardCharsets.UTF_8);
        String typeSegment = "Issue".equals(target.type) ? "issues" : "merge_requests";
        return client.post()
                .uri("/projects/{id}/{type}/{iid}/notes", encodedPath, typeSegment, target.iid)
                .header("PRIVATE-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", text))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(15))
                .doOnNext(resp -> log.debug("GitLab note ok: status={}", resp.getStatusCode()))
                .then();
    }

    /**
     * Parses an outbound address of the form
     * {@code "channelId:thread:path/with/namespace#iid:Issue|MergeRequest"}. Returns null on
     * malformed input.
     */
    private static NoteTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int firstColon = to.indexOf(':');
        if (firstColon < 0) {
            return null;
        }
        int secondColon = to.indexOf(':', firstColon + 1);
        String peerId =
                secondColon < 0 ? to.substring(firstColon + 1) : to.substring(secondColon + 1);

        // peerId format: path/with/namespace#iid:Type
        int hash = peerId.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String path = peerId.substring(0, hash);
        String tail = peerId.substring(hash + 1);
        int colon = tail.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        String iidStr = tail.substring(0, colon);
        String type = tail.substring(colon + 1);
        long iid;
        try {
            iid = Long.parseLong(iidStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (path.isBlank() || iid <= 0 || !("Issue".equals(type) || "MergeRequest".equals(type))) {
            return null;
        }
        return new NoteTarget(path, iid, type);
    }

    private record NoteTarget(String path, long iid, String type) {}
}
