package io.agentscope.builder.web.managed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("unchecked")
public final class SessionUsageService {
    private final SessionNativeLogService logs;
    private final AgentSessionViewStore views;
    private final SessionBudgetService budgets;

    public SessionUsageService(
            SessionNativeLogService logs,
            AgentSessionViewStore views,
            SessionBudgetService budgets) {
        this.logs = logs;
        this.views = views;
        this.budgets = budgets;
    }

    public Map<String, Object> read(ManagedSessionDto session, boolean children) {
        logs.refresh(session.id());
        var ids = new ArrayList<String>();
        ids.add(session.id());
        if (children) ids.addAll(logs.descendants(session).keySet());
        var totals = new LinkedHashMap<String, Number>();
        var models = new ArrayList<Map<String, Object>>();
        var watermarks = new LinkedHashMap<String, Object>();
        for (String id : ids) {
            var view = views.read(id);
            watermarks.put(id, view.get("as_of"));
            var usage = (Map<String, Object>) view.get("usage");
            ((Map<String, Object>) usage.get("totals"))
                    .forEach(
                            (key, value) -> {
                                if (key.equals("time"))
                                    totals.merge(
                                            key,
                                            ((Number) value).doubleValue(),
                                            (a, b) -> a.doubleValue() + b.doubleValue());
                                else
                                    totals.merge(
                                            key,
                                            ((Number) value).longValue(),
                                            (a, b) -> a.longValue() + b.longValue());
                            });
            for (var raw : (List<?>) usage.get("models")) {
                var model = new LinkedHashMap<>((Map<String, Object>) raw);
                model.put("session_id", id);
                models.add(model);
            }
        }
        var usage = new LinkedHashMap<String, Object>();
        usage.put("scope", children ? "session_tree" : "session_only");
        usage.put("totals", totals);
        usage.put("model_calls", models.size());
        usage.put("models", models);
        usage.put("session_watermarks", watermarks);
        return Map.of("data", budgets.priceUsage(usage), "as_of", watermarks.get(session.id()));
    }
}
