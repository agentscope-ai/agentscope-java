package io.agentscope.spring.boot.chat.api;

import java.util.List;

/** Response payload for the Chat Completions HTTP API. */
public class ChatCompletionsResponse {

    private String id;

    private long created;

    private String model;

    private List<ChatChoice> choices;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChatChoice> choices) {
        this.choices = choices;
    }
}
