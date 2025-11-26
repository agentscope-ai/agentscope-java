package io.agentscope.core.tracing;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.common.AttributeKey;

public class AgentScopeIncubatingAttributes {

    static final AttributeKey<String> AGENTSCOPE_FUNCTION_NAME =
            stringKey("agentscope.function.name");

    static final AttributeKey<String> AGENTSCOPE_FUNCTION_INPUT =
            stringKey("agentscope.function.input");

    static final AttributeKey<String> AGENTSCOPE_FUNCTION_OUTPUT =
            stringKey("agentscope.function.output");

    static final AttributeKey<String> AGENTSCOPE_FORMAT_INPUT =
            stringKey("agentscope.format.input");

    static final AttributeKey<String> AGENTSCOPE_FORMAT_OUTPUT =
            stringKey("agentscope.format.output");

    static final AttributeKey<String> AGENTSCOPE_FORMAT_COUNT =
            stringKey("agentscope.format.count");

    static final class GenAiOperationNameAgentScopeIncubatingValues {
        static final String FORMAT = "format";

        static final String INVOKE_GENERIC_FUNCTION = "invoke_generic_function";

        private GenAiOperationNameAgentScopeIncubatingValues() {}
    }

    static final class GenAiProviderNameAgentScopeIncubatingValues {
        static final String DASHSCOPE = "dashscope";

        static final String MOONSHOT = "moonshot";

        private GenAiProviderNameAgentScopeIncubatingValues() {}
    }

    private AgentScopeIncubatingAttributes() {}
}
