/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for the {@link HttpTransportListener} default (no-op) methods. */
@Tag("unit")
class HttpTransportListenerTest {

    @Test
    void defaultMethodsAreSafeNoOps() {
        HttpTransportListener listener = new HttpTransportListener() {};
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/models")
                        .method("GET")
                        .build();
        HttpResponse response = HttpResponse.builder().statusCode(200).body("ok").build();
        RuntimeException failure = new RuntimeException("boom");

        // None of the default implementations may throw.
        listener.onRequest(request);
        listener.onResponse(request, response, 1_000L);
        listener.onFailure(request, failure, 2_000L);
        listener.onStreamComplete(request, 3_000L, 5L);
        listener.onStreamCancel(request, 4_000L, 2L);
    }
}
