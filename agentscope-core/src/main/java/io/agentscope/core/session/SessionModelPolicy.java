package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;

/** Optional hosted admission/accounting policy, shared with inherited child execution contexts. */
public interface SessionModelPolicy {
    String CONTEXT_KEY = "agentscope.session.modelPolicy";

    void beforeCall(String callId, String model, RuntimeContext context);

    void afterCall(
            String callId, String model, String status, ChatUsage usage, RuntimeContext context);
}
