/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable per-agent runtime state.
 *
 * <p>Carries everything a single agent needs to resume mid-conversation: the conversation buffer,
 * a rolling summary, the per-iteration reply identifier, the permission/tool/task sub-contexts,
 * and a counter for the current reasoning iteration.
 *
 * <p>The {@link #context} list is exposed as a defensive copy via {@link #getContext()} and as a
 * live, mutable handle via {@link #contextMutable()}, mirroring the pattern used by
 * {@link TaskContextState}. Storage layers may swap whole {@link AgentState} instances or mutate the
 * inner collections in place.
 *
 * <p>{@code summary} is modelled as a free-form {@link String}; richer structured forms are left
 * for future iterations once context-compression strategies require them.
 */
@JsonPropertyOrder({
    "session_id",
    "user_id",
    "summary",
    "context",
    "reply_id",
    "cur_iter",
    "shutdown_interrupted",
    "permission_context",
    "tool_context",
    "tasks_context",
    "plan_mode_context",
    "model_id"
})
public final class AgentState implements State {

    private final String sessionId;
    private final String userId;
    private String summary;
    private final List<Msg> context;
    private String replyId;
    private int curIter;
    private boolean shutdownInterrupted;
    private PermissionContextState permissionContext;
    private final ToolContextState toolContext;
    private final TaskContextState tasksContext;
    private final PlanModeContextState planModeContext;
    private String modelId;

    /**
     * Per-session interrupt signal. Runtime-only (never serialized): a stateless agent engine
     * attaches one of these per {@code (userId, sessionId)} slot so a targeted {@code interrupt}
     * signals exactly one session's in-flight call. Lazily created and {@code transient} so it is
     * not part of {@code equals}/{@code hashCode} or JSON.
     */
    private transient volatile InterruptControl interruptControl;

    private AgentState(Builder builder) {
        this.sessionId = builder.sessionId == null ? newHex() : builder.sessionId;
        this.userId = builder.userId;
        this.summary = builder.summary == null ? "" : builder.summary;
        this.context = new ArrayList<>(builder.context);
        this.replyId = builder.replyId == null ? newHex() : builder.replyId;
        this.curIter = builder.curIter;
        this.shutdownInterrupted = builder.shutdownInterrupted;
        this.permissionContext =
                builder.permissionContext == null
                        ? PermissionContextState.builder().build()
                        : builder.permissionContext;
        this.toolContext =
                builder.toolContext == null
                        ? ToolContextState.builder().build()
                        : builder.toolContext;
        this.tasksContext =
                builder.tasksContext == null ? new TaskContextState() : builder.tasksContext;
        this.planModeContext =
                builder.planModeContext == null
                        ? new PlanModeContextState()
                        : builder.planModeContext;
        this.modelId = builder.modelId;
    }

    @JsonCreator
    static AgentState fromJson(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("summary") String summary,
            @JsonProperty("context") List<Msg> context,
            @JsonProperty("reply_id") String replyId,
            @JsonProperty("cur_iter") Integer curIter,
            @JsonProperty("shutdown_interrupted") Boolean shutdownInterrupted,
            @JsonProperty("permission_context") PermissionContextState permissionContext,
            @JsonProperty("tool_context") ToolContextState toolContext,
            @JsonProperty("tasks_context") TaskContextState tasksContext,
            @JsonProperty("plan_mode_context") PlanModeContextState planModeContext,
            @JsonProperty("model_id") String modelId) {
        Builder b = builder();
        if (sessionId != null) {
            b.sessionId(sessionId);
        }
        if (userId != null) {
            b.userId(userId);
        }
        if (summary != null) {
            b.summary(summary);
        }
        if (context != null) {
            b.context(context);
        }
        if (replyId != null) {
            b.replyId(replyId);
        }
        if (curIter != null) {
            b.curIter(curIter);
        }
        if (shutdownInterrupted != null) {
            b.shutdownInterrupted(shutdownInterrupted);
        }
        if (permissionContext != null) {
            b.permissionContext(permissionContext);
        }
        if (toolContext != null) {
            b.toolContext(toolContext);
        }
        if (tasksContext != null) {
            b.tasksContext(tasksContext);
        }
        if (planModeContext != null) {
            b.planModeContext(planModeContext);
        }
        if (modelId != null) {
            b.modelId(modelId);
        }
        return b.build();
    }

    private static String newHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @JsonProperty("session_id")
    public String getSessionId() {
        return sessionId;
    }

    /** Nullable; {@code null} = anonymous / single-tenant context. */
    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("summary")
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    /** Defensive copy of the conversation buffer. */
    @JsonProperty("context")
    public List<Msg> getContext() {
        return Collections.unmodifiableList(new ArrayList<>(context));
    }

    /** Live, mutable handle for components that append/replace messages in place. */
    public List<Msg> contextMutable() {
        return context;
    }

    @JsonProperty("reply_id")
    public String getReplyId() {
        return replyId;
    }

    public void setReplyId(String replyId) {
        this.replyId = replyId == null ? newHex() : replyId;
    }

    @JsonProperty("cur_iter")
    public int getCurIter() {
        return curIter;
    }

    public void setCurIter(int curIter) {
        this.curIter = curIter;
    }

    @JsonProperty("shutdown_interrupted")
    public boolean isShutdownInterrupted() {
        return shutdownInterrupted;
    }

    public void setShutdownInterrupted(boolean shutdownInterrupted) {
        this.shutdownInterrupted = shutdownInterrupted;
    }

    @JsonProperty("permission_context")
    public PermissionContextState getPermissionContext() {
        return permissionContext;
    }

    /**
     * Replaces the permission context for this session. Used to change the evaluation mode at
     * runtime (e.g. switching into {@link io.agentscope.core.permission.PermissionMode#BYPASS}).
     * Callers that cache a {@code PermissionEngine} per session must rebuild it after this call.
     *
     * @param permissionContext the new (non-null) permission context
     */
    public void setPermissionContext(PermissionContextState permissionContext) {
        this.permissionContext =
                java.util.Objects.requireNonNull(
                        permissionContext, "permissionContext must not be null");
    }

    @JsonProperty("tool_context")
    public ToolContextState getToolContext() {
        return toolContext;
    }

    @JsonProperty("tasks_context")
    public TaskContextState getTasksContext() {
        return tasksContext;
    }

    @JsonProperty("plan_mode_context")
    public PlanModeContextState getPlanModeContext() {
        return planModeContext;
    }

    @JsonProperty("model_id")
    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = (modelId == null || modelId.isBlank()) ? null : modelId;
    }

    /**
     * The per-session interrupt signal for this state, created on first access. Runtime-only and not
     * serialized.
     */
    @JsonIgnore
    public InterruptControl interruptControl() {
        InterruptControl local = interruptControl;
        if (local == null) {
            synchronized (this) {
                local = interruptControl;
                if (local == null) {
                    local = new InterruptControl();
                    interruptControl = local;
                }
            }
        }
        return local;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serialize this state to a pretty-printed JSON string.
     *
     * <p>Intended for external storage stores to persist the entire agent state as a single
     * JSON document (e.g., {@code agent_state.json}).
     */
    public String toJson() {
        return io.agentscope.core.util.JsonUtils.getJsonCodec().toPrettyJson(this);
    }

    /**
     * Deserialize an {@link AgentState} from a JSON string produced by {@link #toJson()}.
     */
    public static AgentState fromJsonString(String json) {
        return io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(json, AgentState.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AgentState other)) {
            return false;
        }
        return curIter == other.curIter
                && shutdownInterrupted == other.shutdownInterrupted
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(userId, other.userId)
                && Objects.equals(summary, other.summary)
                && Objects.equals(context, other.context)
                && Objects.equals(replyId, other.replyId)
                && Objects.equals(permissionContext, other.permissionContext)
                && Objects.equals(toolContext, other.toolContext)
                && Objects.equals(tasksContext, other.tasksContext)
                && Objects.equals(planModeContext, other.planModeContext)
                && Objects.equals(modelId, other.modelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sessionId,
                userId,
                summary,
                context,
                replyId,
                curIter,
                shutdownInterrupted,
                permissionContext,
                toolContext,
                tasksContext,
                planModeContext,
                modelId);
    }

    @Override
    public String toString() {
        return "AgentState{sessionId="
                + sessionId
                + ", replyId="
                + replyId
                + ", curIter="
                + curIter
                + ", shutdownInterrupted="
                + shutdownInterrupted
                + ", contextSize="
                + context.size()
                + '}';
    }

    public static final class Builder {
        private String sessionId;
        private String userId;
        private String summary;
        private List<Msg> context = new ArrayList<>();
        private String replyId;
        private int curIter;
        private boolean shutdownInterrupted;
        private PermissionContextState permissionContext;
        private ToolContextState toolContext;
        private TaskContextState tasksContext;
        private PlanModeContextState planModeContext;
        private String modelId;

        private Builder() {}

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Nullable; {@code null} = anonymous / single-tenant context. */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder context(List<Msg> context) {
            this.context = context == null ? new ArrayList<>() : new ArrayList<>(context);
            return this;
        }

        public Builder addMessage(Msg message) {
            Objects.requireNonNull(message, "message must not be null");
            this.context.add(message);
            return this;
        }

        public Builder replyId(String replyId) {
            this.replyId = replyId;
            return this;
        }

        public Builder curIter(int curIter) {
            this.curIter = curIter;
            return this;
        }

        public Builder shutdownInterrupted(boolean shutdownInterrupted) {
            this.shutdownInterrupted = shutdownInterrupted;
            return this;
        }

        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        public Builder toolContext(ToolContextState toolContext) {
            this.toolContext = toolContext;
            return this;
        }

        public Builder tasksContext(TaskContextState tasksContext) {
            this.tasksContext = tasksContext;
            return this;
        }

        public Builder planModeContext(PlanModeContextState planModeContext) {
            this.planModeContext = planModeContext;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public AgentState build() {
            return new AgentState(this);
        }
    }
}
