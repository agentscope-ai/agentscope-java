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
package io.agentscope.extensions.durable.http;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Sends a committed event reference with Standard Webhooks headers on an application worker. */
public final class DurableWebhookPublisher implements Consumer<PublicEvent> {
    private static final String HMAC = "HmacSHA256";
    private final URI destination;
    private final SecretKeySpec secret;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient client;

    private record Notification(
            String schemaVersion,
            String eventId,
            String type,
            SessionKey session,
            String threadId,
            String turnId,
            String runId,
            String stepId,
            String sequence,
            String cursor,
            long occurredAt) {}

    public DurableWebhookPublisher(URI destination, byte[] secret, Duration timeout) {
        this(destination, secret, timeout, Clock.systemUTC());
    }

    DurableWebhookPublisher(URI destination, byte[] secret, Duration timeout, Clock clock) {
        this.destination = Objects.requireNonNull(destination);
        if (!"https".equalsIgnoreCase(destination.getScheme())
                && !"http".equalsIgnoreCase(destination.getScheme()))
            throw new IllegalArgumentException("Webhook destination must be an HTTP(S) URL");
        if (secret.length < 24) throw new IllegalArgumentException("Webhook secret is too short");
        this.secret = new SecretKeySpec(secret, HMAC);
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void accept(PublicEvent event) {
        String id = "evt_" + DurableJson.hash(List.of(event.session(), event.eventId()));
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String body =
                DurableJson.value(
                                new Notification(
                                        event.schemaVersion(),
                                        event.eventId(),
                                        event.type(),
                                        event.session(),
                                        event.threadId(),
                                        event.turnId(),
                                        event.runId(),
                                        event.stepId(),
                                        event.sequence(),
                                        event.cursor(),
                                        event.occurredAt()))
                        .toString();
        var request =
                HttpRequest.newBuilder(destination)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("webhook-id", id)
                        .header("webhook-timestamp", timestamp)
                        .header("webhook-signature", signature(id + "." + timestamp + "." + body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300)
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE, "Webhook returned HTTP " + status);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private String signature(String data) {
        try {
            var mac = Mac.getInstance(HMAC);
            mac.init(secret);
            return "v1,"
                    + Base64.getEncoder()
                            .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign webhook delivery", failure);
        }
    }

    private static DurableException unavailable(Exception cause) {
        return new DurableException(
                DurableException.Code.UNAVAILABLE, "Webhook delivery failed", cause);
    }
}
