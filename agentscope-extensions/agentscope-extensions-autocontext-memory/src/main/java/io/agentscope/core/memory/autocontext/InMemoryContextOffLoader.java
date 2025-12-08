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
    Map<String, List<Msg>> messagesMap = new HashMap<>();

    @Override
    public void offload(String uuid, List<Msg> messages) {
        messagesMap.put(uuid, messages);
    }

    @Override
    public List<Msg> reload(String uuid) {
        if (!messagesMap.containsKey(uuid)) {
            return List.of();
        }
        return new ArrayList<>(messagesMap.get(uuid));
    }

    @Override
    public void clear(String uuid) {
        messagesMap.remove(uuid);
    }
}
