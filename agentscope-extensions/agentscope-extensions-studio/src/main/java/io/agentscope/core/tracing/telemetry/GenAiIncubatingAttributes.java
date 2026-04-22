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

package io.agentscope.core.tracing.telemetry;

import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.common.AttributeKey;
import java.util.List;

/**
 * Copied from <a href=https://github.com/open-telemetry/semantic-conventions/blob/v1.37.0/model/gen-ai/registry.yaml>OpenTelemetry semantic conventions 1.37.0</a>.
 * */
public final class GenAiIncubatingAttributes {
    static final AttributeKey<String> GEN_AI_INPUT_MESSAGES = stringKey("gen_ai.input.messages");

    static final AttributeKey<String> GEN_AI_OUTPUT_MESSAGES = stringKey("gen_ai.output.messages");

    static final AttributeKey<String> GEN_AI_TOOL_DEFINITIONS =
            stringKey("gen_ai.tool.definitions");

    static final AttributeKey<String> GEN_AI_SYSTEM_INSTRUCTIONS =
            stringKey("gen_ai.system_instructions");

    static final AttributeKey<String> GEN_AI_AGENT_DESCRIPTION =
            stringKey("gen_ai.agent.description");

    static final AttributeKey<String> GEN_AI_AGENT_ID = stringKey("gen_ai.agent.id");

    static final AttributeKey<String> GEN_AI_AGENT_NAME = stringKey("gen_ai.agent.name");

    static final AttributeKey<String> GEN_AI_CONVERSATION_ID = stringKey("gen_ai.conversation.id");

    static final AttributeKey<String> GEN_AI_DATA_SOURCE_ID = stringKey("gen_ai.data_source.id");

    static final AttributeKey<String> GEN_AI_OPERATION_NAME = stringKey("gen_ai.operation.name");

    static final AttributeKey<String> GEN_AI_OUTPUT_TYPE = stringKey("gen_ai.output.type");

    static final AttributeKey<String> GEN_AI_PROVIDER_NAME = stringKey("gen_ai.provider.name");

    static final AttributeKey<Long> GEN_AI_REQUEST_CHOICE_COUNT =
            longKey("gen_ai.request.choice.count");

    static final AttributeKey<List<String>> GEN_AI_REQUEST_ENCODING_FORMATS =
            stringArrayKey("gen_ai.request.encoding_formats");

    static final AttributeKey<Double> GEN_AI_REQUEST_FREQUENCY_PENALTY =
            doubleKey("gen_ai.request.frequency_penalty");

    static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS =
            longKey("gen_ai.request.max_tokens");

    static final AttributeKey<String> GEN_AI_REQUEST_MODEL = stringKey("gen_ai.request.model");

    static final AttributeKey<Double> GEN_AI_REQUEST_PRESENCE_PENALTY =
            doubleKey("gen_ai.request.presence_penalty");

    static final AttributeKey<Long> GEN_AI_REQUEST_SEED = longKey("gen_ai.request.seed");

    static final AttributeKey<List<String>> GEN_AI_REQUEST_STOP_SEQUENCES =
            stringArrayKey("gen_ai.request.stop_sequences");

    static final AttributeKey<Double> GEN_AI_REQUEST_TEMPERATURE =
            doubleKey("gen_ai.request.temperature");

    static final AttributeKey<Double> GEN_AI_REQUEST_TOP_K = doubleKey("gen_ai.request.top_k");

    static final AttributeKey<Double> GEN_AI_REQUEST_TOP_P = doubleKey("gen_ai.request.top_p");

    static final AttributeKey<List<String>> GEN_AI_RESPONSE_FINISH_REASONS =
            stringArrayKey("gen_ai.response.finish_reasons");

    static final AttributeKey<String> GEN_AI_RESPONSE_ID = stringKey("gen_ai.response.id");

    static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = stringKey("gen_ai.response.model");

    static final AttributeKey<String> GEN_AI_TOKEN_TYPE = stringKey("gen_ai.token.type");

    static final AttributeKey<String> GEN_AI_TOOL_CALL_ID = stringKey("gen_ai.tool.call.id");

    static final AttributeKey<String> GEN_AI_TOOL_DESCRIPTION =
            stringKey("gen_ai.tool.description");

    static final AttributeKey<String> GEN_AI_TOOL_NAME = stringKey("gen_ai.tool.name");

    static final AttributeKey<String> GEN_AI_TOOL_TYPE = stringKey("gen_ai.tool.type");

    static final AttributeKey<String> GEN_AI_TOOL_CALL_ARGUMENTS =
            stringKey("gen_ai.tool.call.arguments");

    static final AttributeKey<String> GEN_AI_TOOL_CALL_RESULT =
            stringKey("gen_ai.tool.call.result");

    static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
            longKey("gen_ai.usage.input_tokens");

    static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
            longKey("gen_ai.usage.output_tokens");

    static final class GenAiOperationNameIncubatingValues {
        static final String CHAT = "chat";

        static final String GENERATE_CONTENT = "generate_content";

        static final String TEXT_COMPLETION = "text_completion";

        static final String EMBEDDINGS = "embeddings";

        static final String CREATE_AGENT = "create_agent";

        static final String INVOKE_AGENT = "invoke_agent";

        static final String EXECUTE_TOOL = "execute_tool";

        private GenAiOperationNameIncubatingValues() {}
    }

    static final class GenAiOutputTypeIncubatingValues {
        static final String TEXT = "text";

        static final String JSON = "json";

        static final String IMAGE = "image";

        static final String SPEECH = "speech";

        private GenAiOutputTypeIncubatingValues() {}
    }

    static final class GenAiProviderNameIncubatingValues {
        static final String OPENAI = "openai";

        static final String GCP_GEN_AI = "gcp.gen_ai";

        static final String GCP_VERTEX_AI = "gcp.vertex_ai";

        static final String GCP_GEMINI = "gcp.gemini";

        static final String ANTHROPIC = "anthropic";

        static final String COHERE = "cohere";

        static final String AZURE_AI_INFERENCE = "azure.ai.inference";

        static final String AZURE_AI_OPENAI = "azure.ai.openai";

        static final String IBM_WATSONX_AI = "ibm.watsonx.ai";

        static final String AWS_BEDROCK = "aws.bedrock";

        static final String PERPLEXITY = "perplexity";

        static final String X_AI = "x_ai";

        static final String DEEPSEEK = "deepseek";

        static final String GROQ = "groq";

        static final String MISTRAL_AI = "mistral_ai";

        private GenAiProviderNameIncubatingValues() {}
    }

    static final class GenAiTokenTypeIncubatingValues {
        static final String INPUT = "input";

        static final String OUTPUT = "output";

        private GenAiTokenTypeIncubatingValues() {}
    }

    private GenAiIncubatingAttributes() {}
}
