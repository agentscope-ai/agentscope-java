/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.protocol;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Desired Session-tree limits, applied before the next model call under an execution grant. */
public record RuntimeBudgetSettings(long revision, Map<String, Object> limits) {
    private static final Set<String> FIELDS =
            Set.of("max_model_calls", "max_total_tokens", "max_cost", "currency");

    public RuntimeBudgetSettings {
        if (revision < 1 || limits == null || !FIELDS.containsAll(limits.keySet()))
            throw new IllegalArgumentException("Invalid budget settings");
        var normalized = new LinkedHashMap<String, Object>();
        for (String key : Set.of("max_model_calls", "max_total_tokens")) {
            Object value = limits.get(key);
            if (value == null) continue;
            if (!(value instanceof Number))
                throw new IllegalArgumentException("Integer budget limit required");
            try {
                long max = new BigDecimal(value.toString()).longValueExact();
                if (max < 1) throw new IllegalArgumentException("Positive budget limit required");
                normalized.put(key, max);
            } catch (ArithmeticException | NumberFormatException error) {
                throw new IllegalArgumentException("Invalid integer budget limit", error);
            }
        }
        if (limits.get("max_cost") != null) {
            String value = String.valueOf(limits.get("max_cost"));
            if (value.length() > 128) throw new IllegalArgumentException("Invalid cost budget");
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() <= 0
                    || amount.precision() > 30
                    || Math.abs((long) amount.scale()) > 30
                    || !(limits.get("currency") instanceof String currency)
                    || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("Positive cost and currency required");
            normalized.put("max_cost", amount);
            normalized.put("currency", limits.get("currency"));
        }
        limits = Map.copyOf(normalized);
    }
}
