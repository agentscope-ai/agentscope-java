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
package io.agentcore.managed.harness.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

/** Shared model shape and semantic constraints. Errors never echo configuration values. */
public final class ModelConfigurationContract {
    private static final ProtocolSchemas SCHEMAS = new ProtocolSchemas();

    private ModelConfigurationContract() {}

    public static void validate(JsonNode model, String harnessType) {
        try {
            SCHEMAS.validate("runtime/ModelConfiguration", model);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid managed model configuration");
        }
        if (model.has("baseUrl")) {
            try {
                var uri = URI.create(model.required("baseUrl").asText());
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || !model.has("credentialEnv")) throw new IllegalArgumentException();
            } catch (Exception invalid) {
                throw new IllegalArgumentException("Invalid model endpoint or credential binding");
            }
        }
        if (model.has("contextWindow")
                && model.has("outputLimit")
                && model.required("outputLimit").asLong()
                        > model.required("contextWindow").asLong())
            throw new IllegalArgumentException("Model outputLimit exceeds contextWindow");
        var parameters = model.path("parameters");
        var limit =
                model.has("outputLimit") ? model.get("outputLimit") : model.get("contextWindow");
        if (parameters.has("maxTokens")
                && limit != null
                && parameters.get("maxTokens").asLong() > limit.asLong())
            throw new IllegalArgumentException("Model maxTokens exceeds token limit");
        if (model.has("reasoning")
                && !model.get("reasoning").asBoolean()
                && parameters.has("reasoning"))
            throw new IllegalArgumentException("Model reasoning effort requires reasoning support");
        if ("anthropic-messages".equals(model.path("api").asText())
                && (parameters.has("parallelToolCalls") || parameters.has("toolChoice")))
            throw new IllegalArgumentException("Tool request controls require an OpenAI API");
        if ("codex".equals(harnessType)) {
            if (model.has("api") && !"openai-responses".equals(model.get("api").asText()))
                throw new IllegalArgumentException("Codex requires model.api openai-responses");
            if (model.has("baseUrl") && !(model.has("contextWindow") && model.has("outputLimit")))
                throw new IllegalArgumentException(
                        "Custom Codex model requires contextWindow and outputLimit");
        }
        if ("agentscope-java".equals(harnessType)) {
            String api = model.path("api").asText();
            if (!("openai-completions".equals(api)
                    || "openai-responses".equals(api)
                    || "anthropic-messages".equals(api)))
                throw new IllegalArgumentException(
                        "AgentScope binding requires an OpenAI or Anthropic API");
            if (!model.has("credentialEnv"))
                throw new IllegalArgumentException("AgentScope binding requires credentialEnv");
            if ("anthropic-messages".equals(api) && parameters.has("reasoning"))
                throw new IllegalArgumentException(
                        "AgentScope Anthropic does not map reasoning effort");
        }
    }
}
