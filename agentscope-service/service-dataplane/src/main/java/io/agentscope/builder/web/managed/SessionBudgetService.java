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

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Session-tree admission quota and estimated usage budget. In-flight tokens are not hard-capped. */
@Service
@SuppressWarnings("unchecked")
public final class SessionBudgetService {
    private final BaseStore store;
    private final SessionUsagePricer prices;
    private final SessionEventLog events;
    private final SessionNativeLogService logs;

    public SessionBudgetService(
            BaseStore store,
            SessionUsagePricer prices,
            SessionEventLog events,
            SessionNativeLogService logs) {
        this.store = store;
        this.prices = prices;
        this.events = events;
        this.logs = logs;
    }

    public record Budget(
            Long max_model_calls, Long max_total_tokens, BigDecimal max_cost, String currency) {}

    private List<String> namespace(String session) {
        return List.of("runtime", "agent-api", "budgets", session);
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(value), Map.class));
    }

    private Map<String, Object> index(Map<String, Object> state, String key) {
        return (Map<String, Object>) state.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
    }

    public Map<String, Object> get(String session) {
        var item = store.get(namespace(session), "ledger");
        var state = item == null ? new LinkedHashMap<String, Object>() : copy(item.value());
        return Map.of(
                "limits",
                index(state, "limits"),
                "usage",
                totals(index(state, "calls")),
                "revision",
                item == null ? 0 : item.version(),
                "scope",
                "session_tree",
                "enforcement",
                "before_model_call");
    }

    public Map<String, Object> configure(String session, Budget input) {
        if (input.max_model_calls() != null && input.max_model_calls() < 1
                || input.max_total_tokens() != null && input.max_total_tokens() < 1
                || input.max_cost() != null
                        && (input.max_cost().signum() <= 0
                                || input.currency() == null
                                || !input.currency().matches("[A-Z]{3}")))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Budget limits must be positive; cost requires a currency");
        var limits = new LinkedHashMap<String, Object>();
        if (input.max_model_calls() != null) limits.put("max_model_calls", input.max_model_calls());
        if (input.max_total_tokens() != null)
            limits.put("max_total_tokens", input.max_total_tokens());
        if (input.max_cost() != null) {
            limits.put("max_cost", input.max_cost());
            limits.put("currency", input.currency());
        }
        for (int i = 0; i < 32; i++) {
            var current = store.get(namespace(session), "ledger");
            var state =
                    current == null ? new LinkedHashMap<String, Object>() : copy(current.value());
            state.put("limits", limits);
            if (store.putIfVersion(
                    namespace(session), "ledger", state, current == null ? 0 : current.version()))
                return get(session);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget changed concurrently");
    }

    public SessionModelPolicy policy(ManagedSessionDto session) {
        return new SessionModelPolicy() {
            public void beforeCall(String call, String model, RuntimeContext context) {
                reconcile(session);
                reserve(session.id(), call, model, context);
            }

            public void afterCall(
                    String call,
                    String model,
                    String status,
                    ChatUsage usage,
                    RuntimeContext context) {
                settle(session.id(), call, model, status, usage);
            }
        };
    }

    /** Repair the accounting suffix from committed native model facts after a worker failure. */
    public void reconcile(ManagedSessionDto session) {
        var initial = store.get(namespace(session.id()), "ledger");
        if (initial == null) return;
        var outstanding = new LinkedHashMap<String, Map<String, Object>>();
        index(copy(initial.value()), "calls")
                .forEach(
                        (id, raw) -> {
                            var call = (Map<String, Object>) raw;
                            if ("reserved".equals(call.get("status"))
                                    || "unknown".equals(call.get("status")))
                                outstanding.put(id, call);
                        });
        if (outstanding.isEmpty()) return;
        var nativeLogs = new LinkedHashMap<String, SessionLog>();
        nativeLogs.put(session.id(), logs.open(session));
        nativeLogs.putAll(logs.descendants(session));
        var repairs = new LinkedHashMap<String, Object>();
        for (var entry : nativeLogs.entrySet()) {
            var dispatched = new HashSet<String>();
            long through = entry.getValue().head().seq();
            for (var fact : entry.getValue().scan(0, through)) {
                String id = String.valueOf(fact.data().get("modelCallId"));
                if (!outstanding.containsKey(id)) continue;
                if (fact.type().equals("model/dispatch")) dispatched.add(id);
                if (fact.type().equals("model/end")) {
                    var repaired = new LinkedHashMap<>(outstanding.get(id));
                    repaired.put("status", fact.data().get("status"));
                    repaired.put(
                            "usage",
                            fact.data().get("usage") == null ? Map.of() : fact.data().get("usage"));
                    repaired.put("usage_reported", fact.data().get("usage") != null);
                    repairs.put(id, repaired);
                }
            }
            var head = entry.getValue().head();
            outstanding.forEach(
                    (id, call) -> {
                        if (repairs.containsKey(id)
                                || head.seq() != through
                                || !entry.getKey().equals(call.get("session_id"))) return;
                        if (call.get("run_id") != null
                                && call.get("run_id").equals(head.owner())
                                && head.leaseUntil() > System.currentTimeMillis()) return;
                        var repaired = new LinkedHashMap<>(call);
                        repaired.put(
                                "status", dispatched.contains(id) ? "unknown" : "not_dispatched");
                        repaired.put("usage", Map.of());
                        repaired.put("usage_reported", !dispatched.contains(id));
                        repairs.put(id, repaired);
                    });
        }
        if (repairs.isEmpty()) return;
        for (int i = 0; i < 32; i++) {
            var current = store.get(namespace(session.id()), "ledger");
            var state = copy(current.value());
            var calls = index(state, "calls");
            repairs.forEach(
                    (id, value) -> {
                        if (calls.get(id) instanceof Map<?, ?> call
                                && ("reserved".equals(call.get("status"))
                                        || "unknown".equals(call.get("status"))))
                            calls.put(id, value);
                    });
            if (store.putIfVersion(namespace(session.id()), "ledger", state, current.version()))
                return;
        }
        throw new IllegalStateException("Budget reconciliation contention");
    }

    private void reserve(String session, String call, String model, RuntimeContext context) {
        for (int i = 0; i < 32; i++) {
            var current = store.get(namespace(session), "ledger");
            var state =
                    current == null ? new LinkedHashMap<String, Object>() : copy(current.value());
            var calls = index(state, "calls");
            var limits = index(state, "limits");
            if (calls.containsKey(call)) return;
            var totals = totals(calls);
            String exceeded = null;
            if (limits.get("max_model_calls") instanceof Number max
                    && calls.size() >= max.longValue()) exceeded = "max_model_calls";
            if (limits.get("max_total_tokens") instanceof Number max) {
                if (((Number) totals.get("unreported_calls")).longValue() > 0)
                    exceeded = "usage_unavailable";
                else if (((Number) totals.get("total_tokens")).longValue() >= max.longValue())
                    exceeded = "max_total_tokens";
            }
            if (limits.get("max_cost") != null) {
                var quote = prices.quote(model, Map.of());
                if (quote == null
                        || !quote.currency().equals(limits.get("currency"))
                        || !(Boolean) totals.get("cost_complete")) exceeded = "pricing_unavailable";
                else if (new BigDecimal(String.valueOf(totals.get("estimated_cost")))
                                .compareTo(new BigDecimal(String.valueOf(limits.get("max_cost"))))
                        >= 0) exceeded = "max_cost";
            }
            if (exceeded != null) {
                var recorder = SessionRecorder.from(context);
                var data = new LinkedHashMap<String, Object>();
                data.put("limit", exceeded);
                data.put("model_call_id", call);
                if (recorder != null) {
                    data.put("turn_id", recorder.turnId());
                    data.put("run_id", recorder.runId());
                }
                var execution = context.get(ManagedTurnContext.class);
                events.appendIdempotentScoped(
                        session,
                        "budget.exceeded",
                        data,
                        "budget_"
                                + JournalSessionLog.hash(call.getBytes(StandardCharsets.UTF_8))
                                        .substring(0, 56),
                        ControlPlaneClient.eventScope(
                                execution == null ? null : execution.scope()));
                throw new BudgetExceededException(exceeded);
            }
            var reservation = new LinkedHashMap<String, Object>();
            reservation.put("model", model);
            reservation.put("status", "reserved");
            reservation.put("created_at", System.currentTimeMillis());
            reservation.put("session_id", context.getSessionId());
            var recorder = SessionRecorder.from(context);
            if (recorder != null) reservation.put("run_id", recorder.runId());
            calls.put(call, reservation);
            if (store.putIfVersion(
                    namespace(session), "ledger", state, current == null ? 0 : current.version()))
                return;
        }
        throw new IllegalStateException("Budget admission contention");
    }

    private void settle(String session, String call, String model, String status, ChatUsage usage) {
        for (int i = 0; i < 32; i++) {
            var current = store.get(namespace(session), "ledger");
            var state =
                    current == null ? new LinkedHashMap<String, Object>() : copy(current.value());
            var contribution = new LinkedHashMap<String, Object>();
            contribution.put("model", model);
            contribution.put("status", status);
            contribution.put(
                    "usage",
                    usage == null
                            ? Map.of()
                            : JsonUtils.getJsonCodec().convertValue(usage, Map.class));
            contribution.put("usage_reported", usage != null);
            index(state, "calls").put(call, contribution);
            if (store.putIfVersion(
                    namespace(session), "ledger", state, current == null ? 0 : current.version()))
                return;
        }
        throw new IllegalStateException("Budget accounting contention");
    }

    private Map<String, Object> totals(Map<String, Object> calls) {
        long tokens = 0, unknown = 0, inflight = 0, unreported = 0;
        BigDecimal cost = BigDecimal.ZERO;
        String currency = null;
        boolean complete = true;
        for (var raw : calls.values()) {
            var call = (Map<String, Object>) raw;
            if ("reserved".equals(call.get("status"))) {
                inflight++;
                continue;
            }
            if (Boolean.FALSE.equals(call.get("usage_reported"))) unreported++;
            var usage = (Map<String, Object>) call.getOrDefault("usage", Map.of());
            tokens +=
                    ((Number) usage.getOrDefault("inputTokens", 0)).longValue()
                            + ((Number) usage.getOrDefault("outputTokens", 0)).longValue();
            var quote = prices.quote((String) call.get("model"), usage);
            if (quote == null
                    || Boolean.FALSE.equals(call.get("usage_reported"))
                    || currency != null && !currency.equals(quote.currency())) {
                complete = false;
                unknown++;
            } else {
                currency = quote.currency();
                cost = cost.add(quote.amount());
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("model_calls", calls.size());
        result.put("total_tokens", tokens);
        result.put("estimated_cost", cost);
        result.put("currency", currency);
        result.put("cost_complete", complete);
        result.put("unreported_calls", unreported);
        result.put("unpriced_calls", unknown);
        result.put("in_flight_calls", inflight);
        return result;
    }

    public Map<String, Object> priceUsage(Map<String, Object> usage) {
        var result = new LinkedHashMap<>(usage);
        var calls = new LinkedHashMap<String, Object>();
        for (var raw : (List<?>) usage.getOrDefault("models", List.of())) {
            var model = (Map<String, Object>) raw;
            var call = new LinkedHashMap<>(model);
            call.put("usage_reported", model.get("usage") != null);
            calls.put(
                    String.valueOf(model.getOrDefault("session_id", ""))
                            + ":"
                            + model.getOrDefault(
                                    "model_call_id",
                                    model.getOrDefault("attempt_id", calls.size())),
                    call);
        }
        var summary = totals(calls);
        result.put("estimated_cost", summary.get("estimated_cost"));
        result.put("currency", summary.get("currency"));
        result.put("cost_complete", summary.get("cost_complete"));
        result.put("unpriced_calls", summary.get("unpriced_calls"));
        return result;
    }

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String limit) {
            super("Session budget prevented model execution: " + limit);
        }
    }
}
