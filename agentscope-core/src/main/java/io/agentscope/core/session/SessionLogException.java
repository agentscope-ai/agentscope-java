package io.agentscope.core.session;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Persistence failures must propagate; they must never become successful model/tool results. */
public class SessionLogException extends RuntimeException {
    public SessionLogException(String message) {
        super(message);
    }

    public SessionLogException(String message, Throwable cause) {
        super(message, cause);
    }

    public static boolean causedBy(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause())
            if (e instanceof SessionLogException) return true;
        return false;
    }
}
