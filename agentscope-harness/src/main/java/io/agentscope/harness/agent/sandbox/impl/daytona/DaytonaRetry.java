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
package io.agentscope.harness.agent.sandbox.impl.daytona;

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.concurrent.Callable;

/** Simple bounded retry for transient Daytona HTTP failures. */
final class DaytonaRetry {

    private DaytonaRetry() {}

    static <T> T withRetries(int maxAttempts, Callable<T> call) throws IOException {
        int n = Math.max(1, maxAttempts);
        IOException last = null;
        for (int i = 0; i < n; i++) {
            try {
                return call.call();
            } catch (SandboxException e) {
                if (!retryable(e) || i == n - 1) {
                    throw e;
                }
                sleepBackoff(i);
            } catch (IOException e) {
                last = e;
                if (i == n - 1) {
                    throw e;
                }
                sleepBackoff(i);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("retry exhausted");
    }

    private static boolean retryable(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : "";
        return m.contains("HTTP 429") || m.contains("HTTP 503") || m.contains("HTTP 502");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
