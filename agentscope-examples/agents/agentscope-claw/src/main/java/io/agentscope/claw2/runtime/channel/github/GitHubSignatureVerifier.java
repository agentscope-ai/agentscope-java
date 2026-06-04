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
package io.agentscope.claw2.runtime.channel.github;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the {@code X-Hub-Signature-256} header on GitHub webhook deliveries: a {@code sha256=}
 * prefix followed by the HMAC-SHA256 of the raw request body keyed with the configured webhook
 * secret.
 *
 * @see <a href="https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries">GitHub: validating webhook deliveries</a>
 */
public final class GitHubSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public GitHubSignatureVerifier(String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret is required");
        }
        this.secret = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns true when {@code header} matches {@code "sha256=" + hex(HMAC-SHA256(secret, body))}.
     * Comparison is constant time.
     */
    public boolean verify(String header, byte[] rawBody) {
        if (header == null || rawBody == null || !header.startsWith(PREFIX)) {
            return false;
        }
        String expectedHex;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            expectedHex = sb.toString();
        } catch (Exception e) {
            return false;
        }
        String got = header.substring(PREFIX.length());
        return constantTimeEquals(expectedHex, got);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
