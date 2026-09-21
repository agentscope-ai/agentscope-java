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

package io.agentscope.extensions.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JevRetryPolicyTest {

    @Test
    void defaultsToTwoRetriesWith500MillisBackoff() {
        JevRetryPolicy policy = JevRetryPolicy.defaults();

        assertEquals(2, policy.maxRetries());
        assertEquals(Duration.ofMillis(500), policy.initialBackoff());
    }

    @Test
    void rejectsInvalidPolicy() {
        IllegalArgumentException negativeRetries =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new JevRetryPolicy(-1, Duration.ofMillis(1)));
        assertTrue(negativeRetries.getMessage().contains("maxRetries"));

        IllegalArgumentException zeroBackoff =
                assertThrows(
                        IllegalArgumentException.class, () -> new JevRetryPolicy(1, Duration.ZERO));
        assertTrue(zeroBackoff.getMessage().contains("initialBackoff"));
    }
}
