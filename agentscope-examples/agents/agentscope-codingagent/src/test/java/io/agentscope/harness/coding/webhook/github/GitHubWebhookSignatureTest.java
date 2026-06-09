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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GitHub webhook HMAC signature verification logic in {@link
 * GitHubWebhookHandler}.
 *
 * <p>Tests access the package-private {@code verifySignature} via reflection so we can cover the
 * signature branch without spinning up a Spring context.
 */
class GitHubWebhookSignatureTest {

    private static final String SECRET = "super-secret";

    @Test
    void validSignature_returnsTrue() throws Exception {
        byte[] body = "{\"action\":\"created\"}".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=" + computeHmac(body, SECRET);
        assertTrue(invokeVerify(body, sig, SECRET));
    }

    @Test
    void wrongSignature_returnsFalse() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        assertFalse(invokeVerify(body, "sha256=deadbeef1234", SECRET));
    }

    @Test
    void missingPrefix_returnsFalse() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String sig = computeHmac(body, SECRET);
        assertFalse(invokeVerify(body, sig, SECRET));
    }

    @Test
    void nullSignature_returnsFalse() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        assertFalse(invokeVerify(body, null, SECRET));
    }

    @Test
    void tamperedBody_returnsFalse() throws Exception {
        byte[] original = "original body".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=" + computeHmac(original, SECRET);
        byte[] tampered = "tampered body".getBytes(StandardCharsets.UTF_8);
        assertFalse(invokeVerify(tampered, sig, SECRET));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static boolean invokeVerify(byte[] body, String sig, String secret) throws Exception {
        Method method =
                GitHubWebhookHandler.class.getDeclaredMethod(
                        "verifySignature", byte[].class, String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, body, sig, secret);
    }

    private static String computeHmac(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
