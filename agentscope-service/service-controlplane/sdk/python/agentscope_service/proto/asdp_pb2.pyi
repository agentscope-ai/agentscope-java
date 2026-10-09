from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ConfigType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CONFIG_TYPE_UNSPECIFIED: _ClassVar[ConfigType]
    CONFIG_TYPE_AGENT: _ClassVar[ConfigType]
    CONFIG_TYPE_TOOL: _ClassVar[ConfigType]
    CONFIG_TYPE_SKILL: _ClassVar[ConfigType]
    CONFIG_TYPE_OVERRIDE: _ClassVar[ConfigType]
    CONFIG_TYPE_MODEL: _ClassVar[ConfigType]
CONFIG_TYPE_UNSPECIFIED: ConfigType
CONFIG_TYPE_AGENT: ConfigType
CONFIG_TYPE_TOOL: ConfigType
CONFIG_TYPE_SKILL: ConfigType
CONFIG_TYPE_OVERRIDE: ConfigType
CONFIG_TYPE_MODEL: ConfigType

class Upstream(_message.Message):
    __slots__ = ("meta", "connect", "config_ack", "session_report", "execution_attempt", "heartbeat", "event_report", "context_report", "inventory", "conversation_turn")
    META_FIELD_NUMBER: _ClassVar[int]
    CONNECT_FIELD_NUMBER: _ClassVar[int]
    CONFIG_ACK_FIELD_NUMBER: _ClassVar[int]
    SESSION_REPORT_FIELD_NUMBER: _ClassVar[int]
    EXECUTION_ATTEMPT_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    EVENT_REPORT_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_REPORT_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_FIELD_NUMBER: _ClassVar[int]
    CONVERSATION_TURN_FIELD_NUMBER: _ClassVar[int]
    meta: UpstreamMeta
    connect: ConnectRequest
    config_ack: ConfigAck
    session_report: SessionReport
    execution_attempt: ExecutionAttemptReport
    heartbeat: Heartbeat
    event_report: EventReport
    context_report: ContextReport
    inventory: InventoryReport
    conversation_turn: ConversationTurnReport
    def __init__(self, meta: _Optional[_Union[UpstreamMeta, _Mapping]] = ..., connect: _Optional[_Union[ConnectRequest, _Mapping]] = ..., config_ack: _Optional[_Union[ConfigAck, _Mapping]] = ..., session_report: _Optional[_Union[SessionReport, _Mapping]] = ..., execution_attempt: _Optional[_Union[ExecutionAttemptReport, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ..., event_report: _Optional[_Union[EventReport, _Mapping]] = ..., context_report: _Optional[_Union[ContextReport, _Mapping]] = ..., inventory: _Optional[_Union[InventoryReport, _Mapping]] = ..., conversation_turn: _Optional[_Union[ConversationTurnReport, _Mapping]] = ...) -> None: ...

class UpstreamMeta(_message.Message):
    __slots__ = ("agent_key", "instance_key", "namespace", "timestamp", "tenant", "agent_id", "binding_id", "generation")
    AGENT_KEY_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_KEY_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    TENANT_FIELD_NUMBER: _ClassVar[int]
    AGENT_ID_FIELD_NUMBER: _ClassVar[int]
    BINDING_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    agent_key: str
    instance_key: str
    namespace: str
    timestamp: int
    tenant: str
    agent_id: str
    binding_id: str
    generation: int
    def __init__(self, agent_key: _Optional[str] = ..., instance_key: _Optional[str] = ..., namespace: _Optional[str] = ..., timestamp: _Optional[int] = ..., tenant: _Optional[str] = ..., agent_id: _Optional[str] = ..., binding_id: _Optional[str] = ..., generation: _Optional[int] = ...) -> None: ...

class ConnectRequest(_message.Message):
    __slots__ = ("runtime", "sdk_version", "capabilities", "session_affinity")
    RUNTIME_FIELD_NUMBER: _ClassVar[int]
    SDK_VERSION_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    SESSION_AFFINITY_FIELD_NUMBER: _ClassVar[int]
    runtime: str
    sdk_version: str
    capabilities: _containers.RepeatedScalarFieldContainer[str]
    session_affinity: str
    def __init__(self, runtime: _Optional[str] = ..., sdk_version: _Optional[str] = ..., capabilities: _Optional[_Iterable[str]] = ..., session_affinity: _Optional[str] = ...) -> None: ...

class ConfigAck(_message.Message):
    __slots__ = ("config_type", "version", "nonce", "accepted", "reject_reason")
    CONFIG_TYPE_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    NONCE_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_FIELD_NUMBER: _ClassVar[int]
    REJECT_REASON_FIELD_NUMBER: _ClassVar[int]
    config_type: ConfigType
    version: str
    nonce: str
    accepted: bool
    reject_reason: str
    def __init__(self, config_type: _Optional[_Union[ConfigType, str]] = ..., version: _Optional[str] = ..., nonce: _Optional[str] = ..., accepted: bool = ..., reject_reason: _Optional[str] = ...) -> None: ...

class SessionReport(_message.Message):
    __slots__ = ("sessions",)
    SESSIONS_FIELD_NUMBER: _ClassVar[int]
    sessions: _containers.RepeatedCompositeFieldContainer[SessionSnapshot]
    def __init__(self, sessions: _Optional[_Iterable[_Union[SessionSnapshot, _Mapping]]] = ...) -> None: ...

class SessionSnapshot(_message.Message):
    __slots__ = ("session_id", "phase", "message_count", "prompt_tokens", "completion_tokens", "context_pressure", "framework", "framework_version", "context_hash", "is_compacted", "effective_message_count")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    PROMPT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    COMPLETION_TOKENS_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_PRESSURE_FIELD_NUMBER: _ClassVar[int]
    FRAMEWORK_FIELD_NUMBER: _ClassVar[int]
    FRAMEWORK_VERSION_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_HASH_FIELD_NUMBER: _ClassVar[int]
    IS_COMPACTED_FIELD_NUMBER: _ClassVar[int]
    EFFECTIVE_MESSAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    phase: str
    message_count: int
    prompt_tokens: int
    completion_tokens: int
    context_pressure: float
    framework: str
    framework_version: str
    context_hash: str
    is_compacted: bool
    effective_message_count: int
    def __init__(self, session_id: _Optional[str] = ..., phase: _Optional[str] = ..., message_count: _Optional[int] = ..., prompt_tokens: _Optional[int] = ..., completion_tokens: _Optional[int] = ..., context_pressure: _Optional[float] = ..., framework: _Optional[str] = ..., framework_version: _Optional[str] = ..., context_hash: _Optional[str] = ..., is_compacted: bool = ..., effective_message_count: _Optional[int] = ...) -> None: ...

class ExecutionAttemptReport(_message.Message):
    __slots__ = ("attempt_id", "agent_task_id", "run_id", "node_id", "generation", "action", "input_ids", "content", "result", "error_code", "error_message", "checkpoint", "usage", "idempotency_key", "attempt_token")
    ATTEMPT_ID_FIELD_NUMBER: _ClassVar[int]
    AGENT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    RUN_ID_FIELD_NUMBER: _ClassVar[int]
    NODE_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    INPUT_IDS_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    ERROR_CODE_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    CHECKPOINT_FIELD_NUMBER: _ClassVar[int]
    USAGE_FIELD_NUMBER: _ClassVar[int]
    IDEMPOTENCY_KEY_FIELD_NUMBER: _ClassVar[int]
    ATTEMPT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    attempt_id: str
    agent_task_id: str
    run_id: str
    node_id: str
    generation: int
    action: str
    input_ids: _containers.RepeatedScalarFieldContainer[str]
    content: str
    result: bytes
    error_code: str
    error_message: str
    checkpoint: bytes
    usage: bytes
    idempotency_key: str
    attempt_token: str
    def __init__(self, attempt_id: _Optional[str] = ..., agent_task_id: _Optional[str] = ..., run_id: _Optional[str] = ..., node_id: _Optional[str] = ..., generation: _Optional[int] = ..., action: _Optional[str] = ..., input_ids: _Optional[_Iterable[str]] = ..., content: _Optional[str] = ..., result: _Optional[bytes] = ..., error_code: _Optional[str] = ..., error_message: _Optional[str] = ..., checkpoint: _Optional[bytes] = ..., usage: _Optional[bytes] = ..., idempotency_key: _Optional[str] = ..., attempt_token: _Optional[str] = ...) -> None: ...

class Heartbeat(_message.Message):
    __slots__ = ("timestamp",)
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    timestamp: int
    def __init__(self, timestamp: _Optional[int] = ...) -> None: ...

class InstanceHealth(_message.Message):
    __slots__ = ("healthy", "reason", "active_sessions", "cpu_usage", "memory_usage")
    HEALTHY_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_SESSIONS_FIELD_NUMBER: _ClassVar[int]
    CPU_USAGE_FIELD_NUMBER: _ClassVar[int]
    MEMORY_USAGE_FIELD_NUMBER: _ClassVar[int]
    healthy: bool
    reason: str
    active_sessions: int
    cpu_usage: float
    memory_usage: float
    def __init__(self, healthy: bool = ..., reason: _Optional[str] = ..., active_sessions: _Optional[int] = ..., cpu_usage: _Optional[float] = ..., memory_usage: _Optional[float] = ...) -> None: ...

class EventReport(_message.Message):
    __slots__ = ("events", "report_id")
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    REPORT_ID_FIELD_NUMBER: _ClassVar[int]
    events: _containers.RepeatedCompositeFieldContainer[SessionEventMsg]
    report_id: str
    def __init__(self, events: _Optional[_Iterable[_Union[SessionEventMsg, _Mapping]]] = ..., report_id: _Optional[str] = ...) -> None: ...

class EventReportAck(_message.Message):
    __slots__ = ("report_id", "committed", "error")
    REPORT_ID_FIELD_NUMBER: _ClassVar[int]
    COMMITTED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    report_id: str
    committed: _containers.RepeatedCompositeFieldContainer[SessionEventCursor]
    error: str
    def __init__(self, report_id: _Optional[str] = ..., committed: _Optional[_Iterable[_Union[SessionEventCursor, _Mapping]]] = ..., error: _Optional[str] = ...) -> None: ...

class SessionEventCursor(_message.Message):
    __slots__ = ("session_id", "committed_seq")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    COMMITTED_SEQ_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    committed_seq: int
    def __init__(self, session_id: _Optional[str] = ..., committed_seq: _Optional[int] = ...) -> None: ...

class SessionEventMsg(_message.Message):
    __slots__ = ("session_id", "seq", "event_type", "occurred_at", "role", "content", "tool_name", "tool_input", "tool_output", "tokens_in", "tokens_out", "duration_ms", "framework_meta")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SEQ_FIELD_NUMBER: _ClassVar[int]
    EVENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    OCCURRED_AT_FIELD_NUMBER: _ClassVar[int]
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    TOOL_NAME_FIELD_NUMBER: _ClassVar[int]
    TOOL_INPUT_FIELD_NUMBER: _ClassVar[int]
    TOOL_OUTPUT_FIELD_NUMBER: _ClassVar[int]
    TOKENS_IN_FIELD_NUMBER: _ClassVar[int]
    TOKENS_OUT_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    FRAMEWORK_META_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    seq: int
    event_type: str
    occurred_at: int
    role: str
    content: str
    tool_name: str
    tool_input: bytes
    tool_output: str
    tokens_in: int
    tokens_out: int
    duration_ms: int
    framework_meta: bytes
    def __init__(self, session_id: _Optional[str] = ..., seq: _Optional[int] = ..., event_type: _Optional[str] = ..., occurred_at: _Optional[int] = ..., role: _Optional[str] = ..., content: _Optional[str] = ..., tool_name: _Optional[str] = ..., tool_input: _Optional[bytes] = ..., tool_output: _Optional[str] = ..., tokens_in: _Optional[int] = ..., tokens_out: _Optional[int] = ..., duration_ms: _Optional[int] = ..., framework_meta: _Optional[bytes] = ...) -> None: ...

class ContextReport(_message.Message):
    __slots__ = ("session_id", "context_hash", "captured_at", "system_prompt", "messages", "tools", "is_compacted", "compaction_summary", "original_message_count", "compacted_at", "total_tokens", "max_tokens", "framework", "framework_state")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_HASH_FIELD_NUMBER: _ClassVar[int]
    CAPTURED_AT_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_PROMPT_FIELD_NUMBER: _ClassVar[int]
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    TOOLS_FIELD_NUMBER: _ClassVar[int]
    IS_COMPACTED_FIELD_NUMBER: _ClassVar[int]
    COMPACTION_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    ORIGINAL_MESSAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    COMPACTED_AT_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TOKENS_FIELD_NUMBER: _ClassVar[int]
    MAX_TOKENS_FIELD_NUMBER: _ClassVar[int]
    FRAMEWORK_FIELD_NUMBER: _ClassVar[int]
    FRAMEWORK_STATE_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    context_hash: str
    captured_at: int
    system_prompt: str
    messages: bytes
    tools: bytes
    is_compacted: bool
    compaction_summary: str
    original_message_count: int
    compacted_at: int
    total_tokens: int
    max_tokens: int
    framework: str
    framework_state: bytes
    def __init__(self, session_id: _Optional[str] = ..., context_hash: _Optional[str] = ..., captured_at: _Optional[int] = ..., system_prompt: _Optional[str] = ..., messages: _Optional[bytes] = ..., tools: _Optional[bytes] = ..., is_compacted: bool = ..., compaction_summary: _Optional[str] = ..., original_message_count: _Optional[int] = ..., compacted_at: _Optional[int] = ..., total_tokens: _Optional[int] = ..., max_tokens: _Optional[int] = ..., framework: _Optional[str] = ..., framework_state: _Optional[bytes] = ...) -> None: ...

class InventoryReport(_message.Message):
    __slots__ = ("subagents", "workspaces", "health")
    SUBAGENTS_FIELD_NUMBER: _ClassVar[int]
    WORKSPACES_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    subagents: _containers.RepeatedCompositeFieldContainer[SubagentInfo]
    workspaces: _containers.RepeatedCompositeFieldContainer[WorkspaceInfo]
    health: InstanceHealth
    def __init__(self, subagents: _Optional[_Iterable[_Union[SubagentInfo, _Mapping]]] = ..., workspaces: _Optional[_Iterable[_Union[WorkspaceInfo, _Mapping]]] = ..., health: _Optional[_Union[InstanceHealth, _Mapping]] = ...) -> None: ...

class SubagentInfo(_message.Message):
    __slots__ = ("name", "description", "tools", "workspace_mode", "url", "invoke_count", "last_invoked_at")
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    TOOLS_FIELD_NUMBER: _ClassVar[int]
    WORKSPACE_MODE_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    INVOKE_COUNT_FIELD_NUMBER: _ClassVar[int]
    LAST_INVOKED_AT_FIELD_NUMBER: _ClassVar[int]
    name: str
    description: str
    tools: _containers.RepeatedScalarFieldContainer[str]
    workspace_mode: str
    url: str
    invoke_count: int
    last_invoked_at: int
    def __init__(self, name: _Optional[str] = ..., description: _Optional[str] = ..., tools: _Optional[_Iterable[str]] = ..., workspace_mode: _Optional[str] = ..., url: _Optional[str] = ..., invoke_count: _Optional[int] = ..., last_invoked_at: _Optional[int] = ...) -> None: ...

class WorkspaceInfo(_message.Message):
    __slots__ = ("path", "mode", "size_bytes", "owner_ref")
    PATH_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    OWNER_REF_FIELD_NUMBER: _ClassVar[int]
    path: str
    mode: str
    size_bytes: int
    owner_ref: str
    def __init__(self, path: _Optional[str] = ..., mode: _Optional[str] = ..., size_bytes: _Optional[int] = ..., owner_ref: _Optional[str] = ...) -> None: ...

class Downstream(_message.Message):
    __slots__ = ("connect_ack", "config_push", "session_cmd", "execution_attempt", "heartbeat", "conversation_turn", "event_ack")
    CONNECT_ACK_FIELD_NUMBER: _ClassVar[int]
    CONFIG_PUSH_FIELD_NUMBER: _ClassVar[int]
    SESSION_CMD_FIELD_NUMBER: _ClassVar[int]
    EXECUTION_ATTEMPT_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    CONVERSATION_TURN_FIELD_NUMBER: _ClassVar[int]
    EVENT_ACK_FIELD_NUMBER: _ClassVar[int]
    connect_ack: ConnectResponse
    config_push: ConfigPush
    session_cmd: SessionCommand
    execution_attempt: ExecutionAttemptCommand
    heartbeat: Heartbeat
    conversation_turn: ConversationTurnCommand
    event_ack: EventReportAck
    def __init__(self, connect_ack: _Optional[_Union[ConnectResponse, _Mapping]] = ..., config_push: _Optional[_Union[ConfigPush, _Mapping]] = ..., session_cmd: _Optional[_Union[SessionCommand, _Mapping]] = ..., execution_attempt: _Optional[_Union[ExecutionAttemptCommand, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ..., conversation_turn: _Optional[_Union[ConversationTurnCommand, _Mapping]] = ..., event_ack: _Optional[_Union[EventReportAck, _Mapping]] = ...) -> None: ...

class ConnectResponse(_message.Message):
    __slots__ = ("accepted", "reject_reason", "control_plane_version")
    ACCEPTED_FIELD_NUMBER: _ClassVar[int]
    REJECT_REASON_FIELD_NUMBER: _ClassVar[int]
    CONTROL_PLANE_VERSION_FIELD_NUMBER: _ClassVar[int]
    accepted: bool
    reject_reason: str
    control_plane_version: str
    def __init__(self, accepted: bool = ..., reject_reason: _Optional[str] = ..., control_plane_version: _Optional[str] = ...) -> None: ...

class ConfigPush(_message.Message):
    __slots__ = ("config_type", "version", "resources", "nonce")
    CONFIG_TYPE_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    RESOURCES_FIELD_NUMBER: _ClassVar[int]
    NONCE_FIELD_NUMBER: _ClassVar[int]
    config_type: ConfigType
    version: str
    resources: bytes
    nonce: str
    def __init__(self, config_type: _Optional[_Union[ConfigType, str]] = ..., version: _Optional[str] = ..., resources: _Optional[bytes] = ..., nonce: _Optional[str] = ...) -> None: ...

class SessionCommand(_message.Message):
    __slots__ = ("session_id", "command", "params")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    PARAMS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    command: str
    params: bytes
    def __init__(self, session_id: _Optional[str] = ..., command: _Optional[str] = ..., params: _Optional[bytes] = ...) -> None: ...

class ConversationTurnCommand(_message.Message):
    __slots__ = ("invocation_id", "conversation_id", "turn_id", "session_id", "agent_id", "binding_id", "instance_id", "generation", "input", "deadline", "correlation_id")
    INVOCATION_ID_FIELD_NUMBER: _ClassVar[int]
    CONVERSATION_ID_FIELD_NUMBER: _ClassVar[int]
    TURN_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    AGENT_ID_FIELD_NUMBER: _ClassVar[int]
    BINDING_ID_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    INPUT_FIELD_NUMBER: _ClassVar[int]
    DEADLINE_FIELD_NUMBER: _ClassVar[int]
    CORRELATION_ID_FIELD_NUMBER: _ClassVar[int]
    invocation_id: str
    conversation_id: str
    turn_id: str
    session_id: str
    agent_id: str
    binding_id: str
    instance_id: str
    generation: int
    input: bytes
    deadline: int
    correlation_id: str
    def __init__(self, invocation_id: _Optional[str] = ..., conversation_id: _Optional[str] = ..., turn_id: _Optional[str] = ..., session_id: _Optional[str] = ..., agent_id: _Optional[str] = ..., binding_id: _Optional[str] = ..., instance_id: _Optional[str] = ..., generation: _Optional[int] = ..., input: _Optional[bytes] = ..., deadline: _Optional[int] = ..., correlation_id: _Optional[str] = ...) -> None: ...

class ConversationTurnReport(_message.Message):
    __slots__ = ("invocation_id", "conversation_id", "turn_id", "session_id", "generation", "action", "sequence", "payload", "error_code", "error_message")
    INVOCATION_ID_FIELD_NUMBER: _ClassVar[int]
    CONVERSATION_ID_FIELD_NUMBER: _ClassVar[int]
    TURN_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    ERROR_CODE_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    invocation_id: str
    conversation_id: str
    turn_id: str
    session_id: str
    generation: int
    action: str
    sequence: int
    payload: bytes
    error_code: str
    error_message: str
    def __init__(self, invocation_id: _Optional[str] = ..., conversation_id: _Optional[str] = ..., turn_id: _Optional[str] = ..., session_id: _Optional[str] = ..., generation: _Optional[int] = ..., action: _Optional[str] = ..., sequence: _Optional[int] = ..., payload: _Optional[bytes] = ..., error_code: _Optional[str] = ..., error_message: _Optional[str] = ...) -> None: ...

class ExecutionAttemptCommand(_message.Message):
    __slots__ = ("attempt_id", "agent_task_id", "run_id", "node_id", "generation", "command", "context_url", "task_token", "attempt_token", "runtime_binding", "payload", "timestamp")
    ATTEMPT_ID_FIELD_NUMBER: _ClassVar[int]
    AGENT_TASK_ID_FIELD_NUMBER: _ClassVar[int]
    RUN_ID_FIELD_NUMBER: _ClassVar[int]
    NODE_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_URL_FIELD_NUMBER: _ClassVar[int]
    TASK_TOKEN_FIELD_NUMBER: _ClassVar[int]
    ATTEMPT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    RUNTIME_BINDING_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    attempt_id: str
    agent_task_id: str
    run_id: str
    node_id: str
    generation: int
    command: str
    context_url: str
    task_token: str
    attempt_token: str
    runtime_binding: bytes
    payload: bytes
    timestamp: int
    def __init__(self, attempt_id: _Optional[str] = ..., agent_task_id: _Optional[str] = ..., run_id: _Optional[str] = ..., node_id: _Optional[str] = ..., generation: _Optional[int] = ..., command: _Optional[str] = ..., context_url: _Optional[str] = ..., task_token: _Optional[str] = ..., attempt_token: _Optional[str] = ..., runtime_binding: _Optional[bytes] = ..., payload: _Optional[bytes] = ..., timestamp: _Optional[int] = ...) -> None: ...
