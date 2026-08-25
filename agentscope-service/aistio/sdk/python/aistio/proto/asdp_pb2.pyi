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
    __slots__ = ("meta", "connect", "config_ack", "session_report", "team_event", "heartbeat", "event_report", "context_report", "inventory")
    META_FIELD_NUMBER: _ClassVar[int]
    CONNECT_FIELD_NUMBER: _ClassVar[int]
    CONFIG_ACK_FIELD_NUMBER: _ClassVar[int]
    SESSION_REPORT_FIELD_NUMBER: _ClassVar[int]
    TEAM_EVENT_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    EVENT_REPORT_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_REPORT_FIELD_NUMBER: _ClassVar[int]
    INVENTORY_FIELD_NUMBER: _ClassVar[int]
    meta: UpstreamMeta
    connect: ConnectRequest
    config_ack: ConfigAck
    session_report: SessionReport
    team_event: TeamEventReport
    heartbeat: Heartbeat
    event_report: EventReport
    context_report: ContextReport
    inventory: InventoryReport
    def __init__(self, meta: _Optional[_Union[UpstreamMeta, _Mapping]] = ..., connect: _Optional[_Union[ConnectRequest, _Mapping]] = ..., config_ack: _Optional[_Union[ConfigAck, _Mapping]] = ..., session_report: _Optional[_Union[SessionReport, _Mapping]] = ..., team_event: _Optional[_Union[TeamEventReport, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ..., event_report: _Optional[_Union[EventReport, _Mapping]] = ..., context_report: _Optional[_Union[ContextReport, _Mapping]] = ..., inventory: _Optional[_Union[InventoryReport, _Mapping]] = ...) -> None: ...

class UpstreamMeta(_message.Message):
    __slots__ = ("agent_name", "instance_id", "namespace", "timestamp")
    AGENT_NAME_FIELD_NUMBER: _ClassVar[int]
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    NAMESPACE_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    agent_name: str
    instance_id: str
    namespace: str
    timestamp: int
    def __init__(self, agent_name: _Optional[str] = ..., instance_id: _Optional[str] = ..., namespace: _Optional[str] = ..., timestamp: _Optional[int] = ...) -> None: ...

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
    def __init__(self, config_type: _Optional[_Union[ConfigType, str]] = ..., version: _Optional[str] = ..., nonce: _Optional[str] = ..., accepted: _Optional[bool] = ..., reject_reason: _Optional[str] = ...) -> None: ...

class SessionReport(_message.Message):
    __slots__ = ("sessions",)
    SESSIONS_FIELD_NUMBER: _ClassVar[int]
    sessions: _containers.RepeatedCompositeFieldContainer[SessionSnapshot]
    def __init__(self, sessions: _Optional[_Iterable[_Union[SessionSnapshot, _Mapping]]] = ...) -> None: ...

class SessionSnapshot(_message.Message):
    __slots__ = ("session_id", "phase", "message_count", "prompt_tokens", "completion_tokens", "context_pressure", "task_summary", "team_id", "team_role", "framework", "framework_version", "context_hash", "is_compacted", "effective_message_count")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_COUNT_FIELD_NUMBER: _ClassVar[int]
    PROMPT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    COMPLETION_TOKENS_FIELD_NUMBER: _ClassVar[int]
    CONTEXT_PRESSURE_FIELD_NUMBER: _ClassVar[int]
    TASK_SUMMARY_FIELD_NUMBER: _ClassVar[int]
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    TEAM_ROLE_FIELD_NUMBER: _ClassVar[int]
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
    task_summary: TaskSummary
    team_id: str
    team_role: str
    framework: str
    framework_version: str
    context_hash: str
    is_compacted: bool
    effective_message_count: int
    def __init__(self, session_id: _Optional[str] = ..., phase: _Optional[str] = ..., message_count: _Optional[int] = ..., prompt_tokens: _Optional[int] = ..., completion_tokens: _Optional[int] = ..., context_pressure: _Optional[float] = ..., task_summary: _Optional[_Union[TaskSummary, _Mapping]] = ..., team_id: _Optional[str] = ..., team_role: _Optional[str] = ..., framework: _Optional[str] = ..., framework_version: _Optional[str] = ..., context_hash: _Optional[str] = ..., is_compacted: _Optional[bool] = ..., effective_message_count: _Optional[int] = ...) -> None: ...

class TaskSummary(_message.Message):
    __slots__ = ("total", "pending", "in_progress", "completed")
    TOTAL_FIELD_NUMBER: _ClassVar[int]
    PENDING_FIELD_NUMBER: _ClassVar[int]
    IN_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_FIELD_NUMBER: _ClassVar[int]
    total: int
    pending: int
    in_progress: int
    completed: int
    def __init__(self, total: _Optional[int] = ..., pending: _Optional[int] = ..., in_progress: _Optional[int] = ..., completed: _Optional[int] = ...) -> None: ...

class TeamEventReport(_message.Message):
    __slots__ = ("team_id", "event_type", "member_name", "task_id", "detail")
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    EVENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    MEMBER_NAME_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    team_id: str
    event_type: str
    member_name: str
    task_id: str
    detail: bytes
    def __init__(self, team_id: _Optional[str] = ..., event_type: _Optional[str] = ..., member_name: _Optional[str] = ..., task_id: _Optional[str] = ..., detail: _Optional[bytes] = ...) -> None: ...

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
    def __init__(self, healthy: _Optional[bool] = ..., reason: _Optional[str] = ..., active_sessions: _Optional[int] = ..., cpu_usage: _Optional[float] = ..., memory_usage: _Optional[float] = ...) -> None: ...

class EventReport(_message.Message):
    __slots__ = ("events",)
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    events: _containers.RepeatedCompositeFieldContainer[SessionEventMsg]
    def __init__(self, events: _Optional[_Iterable[_Union[SessionEventMsg, _Mapping]]] = ...) -> None: ...

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
    def __init__(self, session_id: _Optional[str] = ..., context_hash: _Optional[str] = ..., captured_at: _Optional[int] = ..., system_prompt: _Optional[str] = ..., messages: _Optional[bytes] = ..., tools: _Optional[bytes] = ..., is_compacted: _Optional[bool] = ..., compaction_summary: _Optional[str] = ..., original_message_count: _Optional[int] = ..., compacted_at: _Optional[int] = ..., total_tokens: _Optional[int] = ..., max_tokens: _Optional[int] = ..., framework: _Optional[str] = ..., framework_state: _Optional[bytes] = ...) -> None: ...

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
    __slots__ = ("connect_ack", "config_push", "session_cmd", "team_event", "heartbeat")
    CONNECT_ACK_FIELD_NUMBER: _ClassVar[int]
    CONFIG_PUSH_FIELD_NUMBER: _ClassVar[int]
    SESSION_CMD_FIELD_NUMBER: _ClassVar[int]
    TEAM_EVENT_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    connect_ack: ConnectResponse
    config_push: ConfigPush
    session_cmd: SessionCommand
    team_event: TeamEvent
    heartbeat: Heartbeat
    def __init__(self, connect_ack: _Optional[_Union[ConnectResponse, _Mapping]] = ..., config_push: _Optional[_Union[ConfigPush, _Mapping]] = ..., session_cmd: _Optional[_Union[SessionCommand, _Mapping]] = ..., team_event: _Optional[_Union[TeamEvent, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ...) -> None: ...

class ConnectResponse(_message.Message):
    __slots__ = ("accepted", "reject_reason", "control_plane_version")
    ACCEPTED_FIELD_NUMBER: _ClassVar[int]
    REJECT_REASON_FIELD_NUMBER: _ClassVar[int]
    CONTROL_PLANE_VERSION_FIELD_NUMBER: _ClassVar[int]
    accepted: bool
    reject_reason: str
    control_plane_version: str
    def __init__(self, accepted: _Optional[bool] = ..., reject_reason: _Optional[str] = ..., control_plane_version: _Optional[str] = ...) -> None: ...

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

class TeamEvent(_message.Message):
    __slots__ = ("team_id", "event_type", "member_name", "task_id", "payload", "timestamp")
    TEAM_ID_FIELD_NUMBER: _ClassVar[int]
    EVENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    MEMBER_NAME_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    team_id: str
    event_type: str
    member_name: str
    task_id: str
    payload: bytes
    timestamp: int
    def __init__(self, team_id: _Optional[str] = ..., event_type: _Optional[str] = ..., member_name: _Optional[str] = ..., task_id: _Optional[str] = ..., payload: _Optional[bytes] = ..., timestamp: _Optional[int] = ...) -> None: ...
