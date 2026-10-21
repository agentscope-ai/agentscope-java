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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.protocol.RuntimeBudgetSettings;
import io.agentscope.builder.web.managed.SessionUsagePricer;
import io.agentscope.core.session.SessionEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Indexed accounting. Brain reserves before dispatch; Hands settles from committed model/end. */
public final class ManagedBudgetStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final SessionUsagePricer prices;

    public ManagedBudgetStore(DataSource source, ObjectMapper mapper, SessionUsagePricer prices) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.prices = prices;
    }

    public Map<String, Object> configure(
            ManagedScope requestedScope, Map<String, Object> input, Long expected) {
        var scope = budgetScope(requestedScope);
        var limits = new RuntimeBudgetSettings(1, input).limits();
        return tx.execute(
                ignored -> {
                    lock(scope);
                    var current = budget(scope);
                    long revision = ((Number) current.get("revision")).longValue();
                    if (expected != null && expected != revision)
                        throw new ManagedStateException(
                                "budget_revision_conflict", "Budget changed");
                    if (!json(limits).equals(current.get("limits"))) {
                        jdbc.update(
                                "UPDATE ma.budget SET limits=?,revision=? WHERE scope_key=?",
                                json(limits),
                                revision + 1,
                                scope.messageGroup());
                    }
                    return get(scope);
                });
    }

    public Map<String, Object> get(ManagedScope scope) {
        scope = budgetScope(scope);
        var current = budget(scope);
        return Map.of(
                "root_session_id",
                scope.sessionId(),
                "limits",
                decode((String) current.get("limits")),
                "usage",
                decode((String) current.get("summary")),
                "configuration_revision",
                current.get("revision"),
                "revision",
                current.get("revision"),
                "scope",
                "session_tree",
                "enforcement",
                "before_model_call");
    }

    public void reserve(ManagedTurnExecution execution, String id, String model) {
        execution.check();
        reconcile(execution.scope());
        tx.executeWithoutResult(
                ignored -> {
                    var scope = budgetScope(execution.scope());
                    lock(scope);
                    execution
                            .admission()
                            .requireEpoch(execution.scope(), execution.turnId(), execution.epoch());
                    String quota =
                            jdbc.queryForObject(
                                    "SELECT quota_key FROM ma.session WHERE scope_key=?",
                                    String.class,
                                    execution.scope().messageGroup());
                    var application =
                            jdbc.queryForMap(
                                    "SELECT * FROM ma.admission_quota WHERE quota_key=? FOR UPDATE",
                                    quota);
                    long cap = ((Number) application.get("token_budget")).longValue();
                    long consumed =
                            ((Number) application.get("baseline_tokens_used")).longValue()
                                    + ((Number) application.get("managed_tokens_used")).longValue();
                    if (cap > 0 && consumed >= cap)
                        throw new ManagedStateException(
                                "budget_exceeded", "application_token_budget");
                    var old =
                            jdbc.queryForList(
                                    "SELECT * FROM ma.model_call WHERE scope_key=? AND call_id=?",
                                    scope.messageGroup(),
                                    id);
                    if (!old.isEmpty()) {
                        if (!model.equals(old.get(0).get("model"))
                                || !execution
                                        .scope()
                                        .messageGroup()
                                        .equals(old.get(0).get("source_scope"))
                                || !execution.turnId().equals(old.get(0).get("turn_id"))
                                || ((Number) old.get(0).get("execution_epoch")).longValue()
                                        != execution.epoch())
                            throw new ManagedStateException(
                                    "model_identity_conflict", "Model call content changed");
                        return;
                    }
                    var row = budget(scope);
                    var limits = decode((String) row.get("limits"));
                    var totals = decode((String) row.get("summary"));
                    String denied = null;
                    if (limits.get("max_model_calls") instanceof Number max
                            && number(totals, "model_calls") >= max.longValue())
                        denied = "max_model_calls";
                    if (limits.get("max_total_tokens") instanceof Number max) {
                        if ((number(totals, "unreported_calls") > 0
                                || number(totals, "in_flight_calls") > 0))
                            denied = "usage_unavailable";
                        else if (number(totals, "total_tokens") >= max.longValue())
                            denied = "max_total_tokens";
                    }
                    if (limits.get("max_cost") != null) {
                        var quote = prices.quote(model, Map.of());
                        if (quote == null
                                || !quote.currency().equals(limits.get("currency"))
                                || (number(totals, "unpriced_calls") > 0
                                        || number(totals, "in_flight_calls") > 0))
                            denied = "pricing_unavailable";
                        else if (decimal(totals.getOrDefault("estimated_cost", 0))
                                        .compareTo(decimal(limits.get("max_cost")))
                                >= 0) denied = "max_cost";
                    }
                    if (denied != null) throw new ManagedStateException("budget_exceeded", denied);
                    jdbc.update(
                            "INSERT INTO"
                                + " ma.model_call(scope_key,source_scope,call_id,turn_id,execution_epoch,model,status)"
                                + " VALUES(?,?,?,?,?,?,'reserved')",
                            scope.messageGroup(),
                            execution.scope().messageGroup(),
                            id,
                            execution.turnId(),
                            execution.epoch(),
                            model);
                    totals.put("model_calls", number(totals, "model_calls") + 1);
                    totals.put("in_flight_calls", number(totals, "in_flight_calls") + 1);
                    jdbc.update(
                            "UPDATE ma.budget SET summary=? WHERE scope_key=?",
                            json(totals),
                            scope.messageGroup());
                });
    }

    private void reconcile(ManagedScope requested) {
        tx.executeWithoutResult(
                ignored -> {
                    var scope = budgetScope(requested);
                    lock(scope);
                    var totals = decode((String) budget(scope).get("summary"));
                    var lost =
                            jdbc.queryForList(
                                    "SELECT c.call_id FROM ma.model_call c JOIN ma.turn t ON"
                                            + " c.source_scope=t.scope_key AND c.turn_id=t.turn_id"
                                            + " WHERE c.scope_key=? AND c.status='reserved' AND"
                                            + " (c.execution_epoch<t.execution_epoch OR t.status IN"
                                            + " ('succeeded','failed','cancelled')) LIMIT 32",
                                    scope.messageGroup());
                    for (var call : lost) {
                        jdbc.update(
                                "UPDATE ma.model_call SET status='unknown' WHERE scope_key=? AND"
                                        + " call_id=?",
                                scope.messageGroup(),
                                call.get("call_id"));
                        totals.put(
                                "in_flight_calls",
                                Math.max(0, number(totals, "in_flight_calls") - 1));
                        totals.put("unreported_calls", number(totals, "unreported_calls") + 1);
                        totals.put("unpriced_calls", number(totals, "unpriced_calls") + 1);
                        totals.put("cost_complete", false);
                    }
                    if (!lost.isEmpty())
                        jdbc.update(
                                "UPDATE ma.budget SET summary=? WHERE scope_key=?",
                                json(totals),
                                scope.messageGroup());
                });
    }

    /** Joins the Hands native-fact transaction; duplicated batches never bill twice. */
    public void committed(ManagedScope scope, SessionEvent event) {
        if (!"model/end".equals(event.type())) return;
        var source = scope;
        scope = budgetScope(source);
        var data = event.data();
        Object call = data.get("modelCallId");
        if (!(call instanceof String id)) return;
        var found =
                jdbc.queryForList(
                        "SELECT * FROM ma.model_call WHERE scope_key=? AND call_id=?",
                        scope.messageGroup(),
                        id);
        if (found.isEmpty()) return; // Core auxiliary/local executions may have no model policy.
        var row = found.get(0);
        if (!source.messageGroup().equals(row.get("source_scope"))
                || !event.turnId().equals(row.get("turn_id")))
            throw new ManagedStateException(
                    "model_identity_conflict", "Native accounting source differs");
        boolean wasUnknown = "unknown".equals(row.get("status"));
        if (!wasUnknown && !"reserved".equals(row.get("status"))) return;
        lock(scope);
        var current = budget(scope);
        var totals = decode((String) current.get("summary"));
        var usage =
                data.get("usage") instanceof Map<?, ?> value
                        ? mapper.convertValue(value, new TypeReference<Map<String, Object>>() {})
                        : Map.<String, Object>of();
        boolean reported = data.get("usage") != null;
        var quote = prices.quote((String) row.get("model"), usage);
        boolean priced =
                reported
                        && quote != null
                        && (totals.get("currency") == null
                                || totals.get("currency").equals(quote.currency()));
        if (wasUnknown) {
            totals.put("unreported_calls", Math.max(0, number(totals, "unreported_calls") - 1));
            totals.put("unpriced_calls", Math.max(0, number(totals, "unpriced_calls") - 1));
        } else totals.put("in_flight_calls", Math.max(0, number(totals, "in_flight_calls") - 1));
        totals.put(
                "total_tokens",
                number(totals, "total_tokens")
                        + number(usage, "inputTokens")
                        + number(usage, "outputTokens"));
        totals.put("unreported_calls", number(totals, "unreported_calls") + (reported ? 0 : 1));
        totals.put("unpriced_calls", number(totals, "unpriced_calls") + (priced ? 0 : 1));
        if (priced) {
            if (quote.amount().signum() < 0)
                throw new IllegalArgumentException("Negative model price");
            totals.put(
                    "estimated_cost",
                    decimal(totals.getOrDefault("estimated_cost", 0)).add(quote.amount()));
            totals.put("currency", quote.currency());
        }
        totals.put("cost_complete", number(totals, "unpriced_calls") == 0);
        String quota =
                jdbc.queryForObject(
                        "SELECT quota_key FROM ma.session WHERE scope_key=?",
                        String.class,
                        source.messageGroup());
        jdbc.queryForMap(
                "SELECT quota_key FROM ma.admission_quota WHERE quota_key=? FOR UPDATE", quota);
        jdbc.update(
                "UPDATE ma.admission_quota SET managed_tokens_used=managed_tokens_used+? WHERE"
                        + " quota_key=?",
                number(usage, "inputTokens") + number(usage, "outputTokens"),
                quota);
        jdbc.update(
                "UPDATE ma.model_call SET status=?,usage=? WHERE scope_key=? AND call_id=?",
                String.valueOf(data.get("status")),
                json(usage),
                scope.messageGroup(),
                id);
        jdbc.update(
                "UPDATE ma.budget SET summary=? WHERE scope_key=?",
                json(totals),
                scope.messageGroup());
    }

    private ManagedScope budgetScope(ManagedScope scope) {
        String configuration =
                jdbc.queryForObject(
                        "SELECT configuration FROM ma.session WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        var value = decode(configuration);
        if (value.get("root_session_id") instanceof String root && !root.isBlank())
            return new ManagedScope(scope.tenant(), scope.namespace(), root);
        return scope;
    }

    private void lock(ManagedScope scope) {
        jdbc.queryForObject(
                "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                String.class,
                scope.messageGroup());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.budget WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup())
                == 0)
            jdbc.update(
                    "INSERT INTO ma.budget(scope_key,limits,summary) VALUES(?,'{}','{}')",
                    scope.messageGroup());
    }

    private Map<String, Object> budget(ManagedScope scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM ma.budget WHERE scope_key=?", scope.messageGroup());
        return rows.isEmpty()
                ? Map.of("limits", "{}", "summary", "{}", "revision", 0L)
                : rows.get(0);
    }

    private static long number(Map<String, Object> value, String key) {
        return value.get(key) instanceof Number number ? number.longValue() : 0;
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }
}
