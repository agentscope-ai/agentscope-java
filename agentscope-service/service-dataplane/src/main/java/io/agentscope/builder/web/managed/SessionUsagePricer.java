package io.agentscope.builder.web.managed;

import java.math.BigDecimal;
import java.util.Map;

/** Override this bean to integrate an organization's model pricing catalogue. Null means unknown. */
public interface SessionUsagePricer {
    record Quote(BigDecimal amount, String currency) {}

    Quote quote(String model, Map<String, Object> usage);
}
