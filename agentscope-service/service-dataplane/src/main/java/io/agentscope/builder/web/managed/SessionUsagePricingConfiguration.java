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
