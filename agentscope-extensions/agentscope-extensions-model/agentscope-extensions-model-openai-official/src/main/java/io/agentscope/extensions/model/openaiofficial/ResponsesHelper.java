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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class ResponsesHelper {

    private ResponsesHelper() {}

    /**
     * Extracts response-level metadata (id, status, timestamps, service tier,
     * incomplete details, error) from a terminal {@link Response}.
     *
     * @param response the SDK Response object
     * @return a mutable metadata map containing response-level keys
     */
    static Map<String, Object> extractResponseMetadata(Response response) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put(OpenAIOfficialConstants.MD_RESPONSE_ID, response.id());

        Optional<ResponseStatus> statusOpt = response.status();
        if (statusOpt.isPresent()) {
            metadata.put(OpenAIOfficialConstants.MD_RESPONSE_STATUS, statusOpt.get().asString());
        }

        metadata.put(OpenAIOfficialConstants.MD_RESPONSE_CREATED_AT, response.createdAt());

        Optional<Double> completedAt = response.completedAt();
        if (completedAt.isPresent()) {
            metadata.put(OpenAIOfficialConstants.MD_RESPONSE_COMPLETED_AT, completedAt.get());
        }

        Optional<Response.ServiceTier> serviceTier = response.serviceTier();
        if (serviceTier.isPresent()) {
            metadata.put(
                    OpenAIOfficialConstants.MD_RESPONSE_SERVICE_TIER, serviceTier.get().asString());
        }

        Optional<Response.IncompleteDetails> incompleteDetails = response.incompleteDetails();
        if (incompleteDetails.isPresent()) {
            Optional<Response.IncompleteDetails.Reason> reason = incompleteDetails.get().reason();
            if (reason.isPresent()) {
                metadata.put(
                        OpenAIOfficialConstants.MD_RESPONSE_INCOMPLETE_REASON,
                        reason.get().asString());
            }
        }

        Optional<ResponseError> error = response.error();
        if (error.isPresent()) {
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("message", error.get().message());
            try {
                errorMap.put("code", error.get().code().asString());
            } catch (RuntimeException ignored) {
                // code field may be missing
            }
            metadata.put(OpenAIOfficialConstants.MD_RESPONSE_ERROR, errorMap);
        }

        return metadata;
    }

    /**
     * Extracts usage from a terminal {@link Response}.
     *
     * @param response the SDK Response object
     * @param startTime the start time for wall-clock timing
     * @param metadata the metadata map to write reasoning tokens into
     * @return a {@link ChatUsage}, or {@code null} if the response has no usage
     */
    static ChatUsage extractUsage(
            Response response, Instant startTime, Map<String, Object> metadata) {
        Optional<ResponseUsage> usageOpt = response.usage();
        if (usageOpt.isEmpty()) {
            return null;
        }

        ResponseUsage respUsage = usageOpt.get();
        long inputTokens = respUsage.inputTokens();
        long outputTokens = respUsage.outputTokens();
        long cachedTokens = respUsage.inputTokensDetails().cachedTokens();
        long reasoningTokens = respUsage.outputTokensDetails().reasoningTokens();

        double time = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;

        metadata.put(OpenAIOfficialConstants.MD_USAGE_REASONING_TOKENS, (int) reasoningTokens);

        return ChatUsage.builder()
                .inputTokens((int) inputTokens)
                .outputTokens((int) outputTokens)
                .cachedTokens((int) cachedTokens)
                .time(time)
                .build();
    }
}
