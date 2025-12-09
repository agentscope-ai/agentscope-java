package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory implementation of ContextOffLoader.
 *
 * <p>Stores offloaded context messages in memory using a HashMap. This is the default
 * implementation used when no external storage is configured. Messages are stored
 * temporarily in memory and will be lost when the application restarts.
 *
 * <p>This implementation is suitable for:
 * <ul>
 *   <li>Development and testing environments</li>
 *   <li>Short-lived sessions where persistence is not required</li>
 *   <li>Scenarios where external storage is not available</li>
 * </ul>
 */
public class InMemoryContextOffLoader implements ContextOffLoader {

    /** Map storing offloaded messages by UUID. */
    private final Map<String, List<Msg>> messagesMap = new HashMap<>();

    /**
     * Offloads messages to in-memory storage.
     *
     * <p>Stores the messages in a HashMap keyed by UUID. If a context with the same UUID
     * already exists, it will be replaced.
     *
     * @param uuid the unique identifier for this offloaded context
     * @param messages the messages to offload
     */
    @Override
    public void offload(String uuid, List<Msg> messages) {
        messagesMap.put(uuid, messages);
    }

    /**
     * Reloads messages from in-memory storage.
     *
     * <p>Returns a defensive copy of the messages to prevent external modifications.
     *
     * @param uuid the unique identifier of the offloaded context to retrieve
     * @return a copy of the messages that were offloaded, or an empty list if not found
     */
    @Override
    public List<Msg> reload(String uuid) {
        if (!messagesMap.containsKey(uuid)) {
            return List.of();
        }
        return new ArrayList<>(messagesMap.get(uuid));
    }

    /**
     * Clears messages from in-memory storage.
     *
     * <p>Removes the offloaded context from the HashMap. If the UUID is not found,
     * this operation is a no-op.
     *
     * @param uuid the unique identifier of the offloaded context to clear
     */
    @Override
    public void clear(String uuid) {
        messagesMap.remove(uuid);
    }
}
