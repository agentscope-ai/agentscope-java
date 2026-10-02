package io.agentscope.builder.web.managed;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned session-bound public cursor; native journal positions never appear as SSE ids. */
public final class SessionEventCursor {
    private SessionEventCursor() {}

    public static String encode(String session, long seq) {
        if (seq < 0) throw new IllegalArgumentException("Negative cursor");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("v1\n" + session + "\n" + seq).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String session, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String[] parts =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split("\n", -1);
            if (parts.length != 3 || !parts[0].equals("v1") || !parts[1].equals(session))
                throw new IllegalArgumentException();
            long seq = Long.parseLong(parts[2]);
            if (seq < 0) throw new IllegalArgumentException();
            return seq;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cursor for this session", e);
        }
    }
}
