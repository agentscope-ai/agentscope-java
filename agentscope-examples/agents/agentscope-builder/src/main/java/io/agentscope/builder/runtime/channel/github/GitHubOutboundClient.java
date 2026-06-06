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
package io.agentscope.builder.runtime.channel.github;

import io.agentscope.builder.runtime.channel.OutboundAddress;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * GitHub outbound HTTP client. Posts a comment via
 * {@code POST /repos/{owner}/{repo}/issues/{number}/comments} (works for both Issues and Pull
 * Requests — GitHub treats PR conversation comments as issue comments).
 *
 * <p>Inline PR review-line replies are out of MVP scope.
 *
 * <p>The {@link OutboundAddress#to()} format is {@code "<channelId>:thread:<owner>/<repo>#<number>"},
 * mirroring {@link io.agentscope.builder.runtime.channel.ChannelRouter} output.
 */
public final class GitHubOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubOutboundClient.class);

    private final WebClient client;
    private final String token;

    public GitHubOutboundClient(String apiBase, String token) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.token = token;
    }

    /** Sends each message as a separate comment on the resolved thread. */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        ThreadTarget target = parseAddress(address);
        if (target == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "GitHub outbound: unparseable peerId '" + address.to() + "'"));
        }
        return reactor.core.publisher.Flux.fromIterable(messages)
                .concatMap(msg -> sendOne(target, msg))
                .then();
    }

    private Mono<Void> sendOne(ThreadTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        return client.post()
                .uri(
                        "/repos/{owner}/{repo}/issues/{number}/comments",
                        target.owner,
                        target.repo,
                        target.number)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header(HttpHeaders.USER_AGENT, "agentscope-builder")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", text))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(15))
                .doOnNext(
                        resp -> {
                            String remaining = resp.getHeaders().getFirst("x-ratelimit-remaining");
                            if (remaining != null) {
                                log.debug(
                                        "GitHub send ok: status={}, ratelimit-remaining={}",
                                        resp.getStatusCode(),
                                        remaining);
                            }
                        })
                .then();
    }

    /**
     * Parses an outbound address of the form {@code "channelId:thread:owner/repo#number"}. Returns
     * null on malformed input — caller surfaces an error.
     */
    private static ThreadTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int firstColon = to.indexOf(':');
        if (firstColon < 0) {
            return null;
        }
        int secondColon = to.indexOf(':', firstColon + 1);
        String peerId =
                secondColon < 0 ? to.substring(firstColon + 1) : to.substring(secondColon + 1);
        int hash = peerId.indexOf('#');
        int slash = peerId.indexOf('/');
        if (hash < 0 || slash < 0 || slash > hash) {
            return null;
        }
        String owner = peerId.substring(0, slash);
        String repo = peerId.substring(slash + 1, hash);
        long number;
        try {
            number = Long.parseLong(peerId.substring(hash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            return null;
        }
        return new ThreadTarget(owner, repo, number);
    }

    private record ThreadTarget(String owner, String repo, long number) {}
}
