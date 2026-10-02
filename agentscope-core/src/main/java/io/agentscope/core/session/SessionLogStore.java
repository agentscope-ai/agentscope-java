package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/** Extensible session log backend. Opening a reader must not acquire a writer or repair history. */
@FunctionalInterface
public interface SessionLogStore {
    SessionLog open(SessionKey key, RuntimeContext context);

    /** Discover native sessions in the caller's storage namespace, without acquiring writers.
     * Implementations should restrict results to the caller's user identity. This is a discovery
     * view, not a transactional snapshot; a session created concurrently may appear on a later call.
     */
    default List<SessionKey> list(RuntimeContext context) {
        throw new UnsupportedOperationException(
                "This session log store does not support discovery");
    }
}
