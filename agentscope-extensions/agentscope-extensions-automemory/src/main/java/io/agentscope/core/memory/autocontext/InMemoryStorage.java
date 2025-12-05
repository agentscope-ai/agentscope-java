package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryStorage implements MemoryStorage {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    String sessionId;

    public InMemoryStorage(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    @Override
    public void clear() {
        messages.clear();
    }
}
