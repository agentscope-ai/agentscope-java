package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryContextOffLoader implements ContextOffLoader {

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
