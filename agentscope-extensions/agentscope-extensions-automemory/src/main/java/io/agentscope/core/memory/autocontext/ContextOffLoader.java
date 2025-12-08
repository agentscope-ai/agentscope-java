package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Context offloader interface. Supports offload and reload operations.
 */
public interface ContextOffLoader {

    /**
     * offload messages to storage
     * @param uuid
     * @param messages
     */
    void offload(String uuid, List<Msg> messages);

    /**
     * reload messages from storage
     * @param uuid
     * @return
     */
    List<Msg> reload(String uuid);

    /**
     * clear messages from storage
     * @param uuid
     */
    void clear(String uuid);
}
