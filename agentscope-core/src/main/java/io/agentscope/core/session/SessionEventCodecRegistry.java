package io.agentscope.core.session;

import io.agentscope.core.state.AgentState;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Explicit extension schemas. Required unknown versions/types always stop recovery. */
public final class SessionEventCodecRegistry {
    private static final SessionEventCodecRegistry DEFAULT = new SessionEventCodecRegistry();
    private final Map<String, Codec> extensions = new ConcurrentHashMap<>();

    public record Codec(
            Consumer<Map<String, Object>> validate,
            BiConsumer<AgentState, Map<String, Object>> apply) {
        public Codec {
            Objects.requireNonNull(validate);
            Objects.requireNonNull(apply);
        }
    }

    public static SessionEventCodecRegistry defaultRegistry() {
        return DEFAULT;
    }

    public void register(String type, Codec codec) {
        if (type == null || !type.contains("/") || SessionEventTypes.BUILTIN.contains(type))
            throw new IllegalArgumentException("Use a non-reserved namespaced event type");
        if (extensions.putIfAbsent(type, Objects.requireNonNull(codec)) != null)
            throw new IllegalArgumentException("Event codec already registered: " + type);
    }

    public void validate(SessionEvent event) {
        Codec codec = extensions.get(event.type());
        if (codec != null) codec.validate().accept(event.data());
        else if (event.required() && !SessionEventTypes.BUILTIN.contains(event.type()))
            throw new SessionLogException("Unknown required event: " + event.type());
    }

    public void applyExtension(AgentState state, SessionEvent event) {
        Codec codec = extensions.get(event.type());
        if (codec != null) codec.apply().accept(state, event.data());
    }
}
