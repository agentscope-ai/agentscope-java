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
package io.agentscope.core.model;

import io.agentscope.core.event.ModelCallAttemptFailureCategory;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Classifies model call exceptions into structured failure categories for attempt events.
 *
 * <p>All classification is based on exception type and HTTP status code. No raw response
 * bodies, credential data, or sensitive headers are exposed.
 */
public final class ModelCallFailureClassifier {

    private ModelCallFailureClassifier() {}

    /**
     * Pattern matching "Bearer &lt;token&gt;" — the most common credential leak format.
     * Matches "Bearer" followed by one or more spaces and the token value.
     */
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)Bearer\\s+\\S+");

    /**
     * Pattern matching key-value credential patterns like "Api-Key: xxx",
     * "Authorization: xxx", "Token=xxx", "api_key xxx", "Secret: xxx", etc.
     */
    private static final Pattern KEY_VALUE_CREDENTIAL_PATTERN =
            Pattern.compile(
                    "(?i)(Api-?Key|Authorization|Token|Secret|api_key|apikey)" + "[\\s:=]+\\S+");

    /** Pattern matching response body sections appended by HttpTransportException. */
    private static final Pattern RESPONSE_BODY_PATTERN =
            Pattern.compile("\\s*[|\\n]\\s*Response body:.*", Pattern.DOTALL);

    /**
     * Classifies the given throwable into a failure category.
     *
     * @param error the exception to classify
     * @return the failure category, never null
     */
    public static ModelCallAttemptFailureCategory classify(Throwable error) {
        if (error == null) {
            return ModelCallAttemptFailureCategory.UNKNOWN;
        }

        // Check cause chain recursively (max 10 levels to avoid loops)
        return classifyInternal(error, 0);
    }

    private static ModelCallAttemptFailureCategory classifyInternal(Throwable error, int depth) {
        if (error == null || depth > 10) {
            return ModelCallAttemptFailureCategory.UNKNOWN;
        }

        if (error instanceof HttpTransportException hte) {
            return classifyHttpTransport(hte);
        }

        if (error instanceof ModelHttpException mhe) {
            return classifyModelHttp(mhe);
        }

        if (error instanceof TimeoutException || error instanceof SocketTimeoutException) {
            return ModelCallAttemptFailureCategory.TIMEOUT;
        }

        if (error instanceof ConnectException) {
            return ModelCallAttemptFailureCategory.NETWORK;
        }

        if (error instanceof IOException) {
            return ModelCallAttemptFailureCategory.NETWORK;
        }

        if (error instanceof ModelException) {
            String message = error.getMessage();
            if (message != null && message.contains("timeout")) {
                return ModelCallAttemptFailureCategory.TIMEOUT;
            }
            return ModelCallAttemptFailureCategory.UNKNOWN;
        }

        // Recurse into cause
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return classifyInternal(cause, depth + 1);
        }

        return ModelCallAttemptFailureCategory.UNKNOWN;
    }

    private static ModelCallAttemptFailureCategory classifyHttpTransport(
            HttpTransportException hte) {
        Integer status = hte.getStatusCode();
        if (status == null) {
            return ModelCallAttemptFailureCategory.NETWORK;
        }
        return classifyStatusCode(status);
    }

    private static ModelCallAttemptFailureCategory classifyModelHttp(ModelHttpException mhe) {
        Integer status = mhe.getStatusCode();
        if (status == null) {
            return ModelCallAttemptFailureCategory.UNKNOWN;
        }
        return classifyStatusCode(status);
    }

    private static ModelCallAttemptFailureCategory classifyStatusCode(int status) {
        return switch (status) {
            case 401 -> ModelCallAttemptFailureCategory.AUTHENTICATION;
            case 403 -> ModelCallAttemptFailureCategory.AUTHORIZATION;
            case 429 -> ModelCallAttemptFailureCategory.RATE_LIMIT;
            default -> {
                if (status >= 400 && status < 500) {
                    yield ModelCallAttemptFailureCategory.INVALID_REQUEST;
                }
                if (status >= 500 && status < 600) {
                    yield ModelCallAttemptFailureCategory.PROVIDER_5XX;
                }
                yield ModelCallAttemptFailureCategory.UNKNOWN;
            }
        };
    }

    /**
     * Returns whether the given error is retryable based on its failure category.
     *
     * @param error the exception to check
     * @return true if the error should trigger a retry
     */
    public static boolean isRetryable(Throwable error) {
        return ExecutionConfig.RETRYABLE_ERRORS.test(error);
    }

    /**
     * Extracts a sanitized error code string from the exception (HTTP status code or
     * exception class name). No credential or response body data is included.
     *
     * @param error the exception
     * @return a short, safe error code string
     */
    public static String sanitizeErrorCode(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof HttpTransportException hte && hte.getStatusCode() != null) {
            return "HTTP_" + hte.getStatusCode();
        }
        if (error instanceof ModelHttpException mhe && mhe.getStatusCode() != null) {
            return "HTTP_" + mhe.getStatusCode();
        }
        return error.getClass().getSimpleName();
    }

    /**
     * Extracts a sanitized, short error message suitable for event payloads.
     *
     * <p>The message contains only the exception's top-level message with no response
     * bodies, headers, or credential data. For {@link HttpTransportException} and any
     * exception whose message includes a "Response body:" section, the response body is
     * stripped. Credential patterns (Bearer tokens, API keys, etc.) are replaced with
     * {@code [REDACTED]}. Truncated to 200 characters.
     *
     * @param error the exception
     * @return a safe, truncated error message
     */
    public static String sanitizeMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        // Strip any "Response body:" section (handles both "| Response body:" and
        // "\nResponse body:" formats from HttpTransportException and similar)
        message = RESPONSE_BODY_PATTERN.matcher(message).replaceAll("");
        // Redact "Bearer <token>" patterns first (most common leak format)
        message = BEARER_TOKEN_PATTERN.matcher(message).replaceAll("Bearer [REDACTED]");
        // Redact key-value credential patterns like "Authorization: xxx", "Api-Key=xxx"
        message = KEY_VALUE_CREDENTIAL_PATTERN.matcher(message).replaceAll("$1 [REDACTED]");
        // Take only the first line
        int newline = message.indexOf('\n');
        if (newline > 0) {
            message = message.substring(0, newline);
        }
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        return message;
    }
}
