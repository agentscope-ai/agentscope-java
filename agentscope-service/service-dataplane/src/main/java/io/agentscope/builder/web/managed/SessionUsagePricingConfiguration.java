package io.agentscope.builder.web.managed;

import io.agentscope.core.util.JsonUtils;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionUsagePricingConfiguration {
    @Bean
    @ConditionalOnMissingBean(SessionUsagePricer.class)
    public SessionUsagePricer sessionUsagePricer(
            @Value("${builder.agent-api.pricing.models:{}}") String configuration,
            @Value("${builder.agent-api.pricing.currency:USD}") String currency) {
        var prices = JsonUtils.getJsonCodec().fromJson(configuration, Map.class);
        return (model, usage) -> {
            if (!(prices.get(model) instanceof Map<?, ?> rate)) return null;
            if (!rate.containsKey("input_per_million") || !rate.containsKey("output_per_million"))
                return null;
            long input = ((Number) usage.getOrDefault("inputTokens", 0)).longValue();
            long output = ((Number) usage.getOrDefault("outputTokens", 0)).longValue();
            var inRate = new BigDecimal(String.valueOf(rate.get("input_per_million")));
            var outRate = new BigDecimal(String.valueOf(rate.get("output_per_million")));
            if (inRate.signum() < 0 || outRate.signum() < 0)
                throw new IllegalArgumentException("Negative model price");
            var cost =
                    inRate.multiply(BigDecimal.valueOf(input))
                            .add(outRate.multiply(BigDecimal.valueOf(output)))
                            .divide(BigDecimal.valueOf(1_000_000));
            return new SessionUsagePricer.Quote(cost, currency);
        };
    }
}
