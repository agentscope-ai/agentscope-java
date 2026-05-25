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
package io.agentscope.harness.coding.webhook.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.coding.control.RunDispatcher;
import io.agentscope.harness.coding.control.ThreadIdFactory;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import io.agentscope.harness.coding.store.SqliteBaseStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Spring WebFlux handler for GitHub webhooks.
 *
 * <p>Mirrors open-swe's {@code agent/webapp.py} GitHub webhook processing:
 *
 * <ul>
 *   <li>HMAC SHA-256 signature verification ({@code X-Hub-Signature-256})
 *   <li>Delivery dedup ({@code X-GitHub-Delivery})
 *   <li>Event routing: issue comment, PR comment, review_requested, push (watch)
 *   <li>Thread ID generation via {@link ThreadIdFactory}
 *   <li>Dispatch to coding or reviewer agent via {@link RunDispatcher}
 * </ul>
 */
@RestController
public class GitHubWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookHandler.class);

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Set<String> ALLOWED_EVENTS =
            Set.of("issue_comment", "pull_request", "pull_request_review_comment", "push");

    private final RunDispatcher dispatcher;
    private final SqliteBaseStore store;
    private final CodingAgentMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubWebhookHandler(RunDispatcher dispatcher, SqliteBaseStore store) {
        this(dispatcher, store, null);
    }

    public GitHubWebhookHandler(
            RunDispatcher dispatcher, SqliteBaseStore store, CodingAgentMetrics metrics) {
        this.dispatcher = dispatcher;
        this.store = store;
        this.metrics = metrics;
    }

    @PostMapping("/webhooks/github")
    public Mono<ResponseEntity<String>> handleGitHubEvent(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", defaultValue = "unknown") String event,
            @RequestHeader(name = "X-GitHub-Delivery", defaultValue = "") String deliveryId,
            @RequestBody byte[] rawBody) {

        if (metrics != null) {
            metrics.recordWebhookReceived();
        }

        // 1. Verify signature
        String webhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET");
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (!verifySignature(rawBody, signature, webhookSecret)) {
                log.warn("[github-webhook] Invalid signature for delivery={}", deliveryId);
                return Mono.just(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature"));
            }
        }

        // 2. Dedup
        if (!deliveryId.isBlank()) {
            List<String> dedupNs = List.of("deliveries");
            if (store.get(dedupNs, deliveryId) != null) {
                if (metrics != null) {
                    metrics.recordWebhookDuplicate();
                }
                log.debug("[github-webhook] Duplicate delivery={}", deliveryId);
                return Mono.just(ResponseEntity.ok("Already processed"));
            }
            store.put(dedupNs, deliveryId, Map.of("processed_at", System.currentTimeMillis()));
        }

        // 3. Skip irrelevant events
        if (!ALLOWED_EVENTS.contains(event)) {
            return Mono.just(ResponseEntity.ok("Ignored event: " + event));
        }

        // 4. Parse payload and dispatch
        return Mono.fromCallable(() -> mapper.readTree(rawBody))
                .flatMap(payload -> routeEvent(event, payload))
                .thenReturn(ResponseEntity.ok("OK"))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[github-webhook] Error processing event={} delivery={}: {}",
                                    event,
                                    deliveryId,
                                    e.getMessage(),
                                    e);
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                            .body("Error: " + e.getMessage()));
                        });
    }

    @org.springframework.web.bind.annotation.GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("OK"));
    }

    // -----------------------------------------------------------------
    //  Event routing
    // -----------------------------------------------------------------

    private Mono<Void> routeEvent(String event, JsonNode payload) {
        return switch (event) {
            case "issue_comment" -> handleIssueComment(payload);
            case "pull_request" -> handlePullRequest(payload);
            case "pull_request_review_comment" -> handlePrReviewComment(payload);
            case "push" -> handlePush(payload);
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleIssueComment(JsonNode payload) {
        String action = payload.path("action").asText("");
        if (!"created".equals(action)) return Mono.empty();

        JsonNode issue = payload.path("issue");
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("login").asText();
        String repoName = repo.path("name").asText();
        int number = issue.path("number").asInt();
        String comment = payload.path("comment").path("body").asText();
        String commenter = payload.path("comment").path("user").path("login").asText();

        if (isSelfComment(commenter)) {
            log.debug(
                    "[github-webhook] Skipping self-comment from bot {} on {}/{}#{}",
                    commenter,
                    owner,
                    repoName,
                    number);
            return Mono.empty();
        }

        String threadId = ThreadIdFactory.fromGitHubIssue(owner, repoName, number);
        String prompt =
                buildIssueCommentPrompt(owner, repoName, number, comment, commenter, payload);

        log.info(
                "[github-webhook] issue_comment: {}/{}#{} by {}",
                owner,
                repoName,
                number,
                commenter);
        return dispatcher.dispatch(threadId, "coding", prompt, null, commenter);
    }

    private Mono<Void> handlePullRequest(JsonNode payload) {
        String action = payload.path("action").asText("");
        if (!"review_requested".equals(action) && !"opened".equals(action)) return Mono.empty();

        JsonNode pr = payload.path("pull_request");
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("login").asText();
        String repoName = repo.path("name").asText();
        int prNumber = pr.path("number").asInt();
        String prUrl = pr.path("html_url").asText();

        if ("review_requested".equals(action)) {
            String requester = payload.path("sender").path("login").asText("");
            String threadId = ThreadIdFactory.fromGitHubReviewer(owner, repoName, prNumber);
            String prompt = "Please review the following pull request: " + prUrl;
            log.info("[github-webhook] review_requested: {}/{}#{}", owner, repoName, prNumber);
            return dispatcher.dispatch(threadId, "reviewer", prompt, null, requester);
        }
        return Mono.empty();
    }

    private Mono<Void> handlePrReviewComment(JsonNode payload) {
        String action = payload.path("action").asText("");
        if (!"created".equals(action)) return Mono.empty();

        JsonNode pr = payload.path("pull_request");
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("login").asText();
        String repoName = repo.path("name").asText();
        int prNumber = pr.path("number").asInt();
        String comment = payload.path("comment").path("body").asText();
        String commenter = payload.path("comment").path("user").path("login").asText();

        if (isSelfComment(commenter)) {
            log.debug(
                    "[github-webhook] Skipping self-comment from bot {} on {}/{}#{}",
                    commenter,
                    owner,
                    repoName,
                    prNumber);
            return Mono.empty();
        }

        String threadId = ThreadIdFactory.fromGitHubPr(owner, repoName, prNumber);
        String prompt =
                buildPrCommentPrompt(owner, repoName, prNumber, comment, commenter, payload);

        log.info(
                "[github-webhook] pr_review_comment: {}/{}#{} by {}",
                owner,
                repoName,
                prNumber,
                commenter);
        return dispatcher.dispatch(threadId, "coding", prompt, null, commenter);
    }

    private Mono<Void> handlePush(JsonNode payload) {
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("login").asText();
        String repoName = repo.path("name").asText();
        String ref = payload.path("ref").asText("");
        String headSha = payload.path("after").asText();

        if (!ref.startsWith("refs/heads/")) return Mono.empty();

        log.debug("[github-webhook] push: {}/{} ref={} sha={}", owner, repoName, ref, headSha);
        return Mono.empty();
    }

    // -----------------------------------------------------------------
    //  Prompt building
    // -----------------------------------------------------------------

    private String buildIssueCommentPrompt(
            String owner,
            String repo,
            int number,
            String comment,
            String commenter,
            JsonNode payload) {
        String issueTitle = payload.path("issue").path("title").asText("");
        return String.format(
                "GitHub issue comment on %s/%s#%d (%s):\n\n%s posted:\n\n%s",
                owner, repo, number, issueTitle, commenter, wrapUntrusted(comment));
    }

    private String buildPrCommentPrompt(
            String owner,
            String repo,
            int number,
            String comment,
            String commenter,
            JsonNode payload) {
        String prTitle = payload.path("pull_request").path("title").asText("");
        return String.format(
                "GitHub PR review comment on %s/%s#%d (%s):\n\n%s posted:\n\n%s",
                owner, repo, number, prTitle, commenter, wrapUntrusted(comment));
    }

    /**
     * Wraps an external comment body in {@code <UNTRUSTED_GITHUB_COMMENT>} tags so the model
     * treats it as data — not as instructions to follow. Mirrors open-swe's prompt-injection
     * defence.
     */
    private static String wrapUntrusted(String body) {
        String safe = body == null ? "" : body;
        return "<UNTRUSTED_GITHUB_COMMENT>\n" + safe + "\n</UNTRUSTED_GITHUB_COMMENT>";
    }

    /**
     * Returns {@code true} when the commenter login matches {@code GITHUB_BOT_LOGIN}, so the
     * agent doesn't endlessly react to comments it just posted itself.
     */
    private static boolean isSelfComment(String commenter) {
        String botLogin = System.getenv("GITHUB_BOT_LOGIN");
        if (botLogin == null || botLogin.isBlank() || commenter == null || commenter.isBlank()) {
            return false;
        }
        return commenter.equalsIgnoreCase(botLogin.trim());
    }

    // -----------------------------------------------------------------
    //  Signature verification
    // -----------------------------------------------------------------

    private static boolean verifySignature(byte[] body, String signature, String secret) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] expected = mac.doFinal(body);
            String expectedHex = bytesToHex(expected);
            String received = signature.substring("sha256=".length());
            return constantTimeEquals(expectedHex, received);
        } catch (Exception e) {
            log.error("[github-webhook] Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
