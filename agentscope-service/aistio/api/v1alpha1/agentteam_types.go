package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// TeamPhase represents the lifecycle phase of a team.
// +kubebuilder:validation:Enum=Pending;Running;Completed;Failed
type TeamPhase string

const (
	TeamPhasePending   TeamPhase = "Pending"
	TeamPhaseRunning   TeamPhase = "Running"
	TeamPhaseCompleted TeamPhase = "Completed"
	TeamPhaseFailed    TeamPhase = "Failed"
)

// MemberPhase represents the state of a team member.
// +kubebuilder:validation:Enum=Joining;Working;Idle;Lost;Failed;Shutdown
type MemberPhase string

const (
	MemberPhaseJoining  MemberPhase = "Joining"
	MemberPhaseWorking  MemberPhase = "Working"
	MemberPhaseIdle     MemberPhase = "Idle"
	MemberPhaseLost     MemberPhase = "Lost"
	MemberPhaseFailed   MemberPhase = "Failed"
	MemberPhaseShutdown MemberPhase = "Shutdown"
)

// MemberOrigin indicates how a member was added to the team.
// +kubebuilder:validation:Enum=static;dynamic
type MemberOrigin string

const (
	MemberOriginStatic  MemberOrigin = "static"
	MemberOriginDynamic MemberOrigin = "dynamic"
)

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:shortName=at
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Lead",type=string,JSONPath=`.spec.lead.agentRef.name`
// +kubebuilder:printcolumn:name="Members",type=integer,JSONPath=`.status.tasks.total`,priority=1
// +kubebuilder:printcolumn:name="Tasks",type=string,JSONPath=`.status.tasks.completed`,priority=1
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// AgentTeam represents a collaborative team of agents.
type AgentTeam struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   AgentTeamSpec   `json:"spec,omitempty"`
	Status AgentTeamStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// AgentTeamList contains a list of AgentTeam.
type AgentTeamList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []AgentTeam `json:"items"`
}

// AgentTeamSpec defines the desired state of AgentTeam.
type AgentTeamSpec struct {
	// Objective is the team goal, injected into all members' system prompts.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:MaxLength=4096
	Objective string `json:"objective"`

	// +kubebuilder:validation:Required
	Lead TeamLeadSpec `json:"lead"`

	// Static members, spawned at team creation.
	// +optional
	Members []TeamMemberSpec `json:"members,omitempty"`

	// Dynamic membership policy.
	// +optional
	DynamicMembers *DynamicMembersSpec `json:"dynamicMembers,omitempty"`

	// +optional
	SharedContext *SharedContextSpec `json:"sharedContext,omitempty"`

	// +optional
	Recovery *RecoverySpec `json:"recovery,omitempty"`

	// +optional
	Lifecycle *TeamLifecycle `json:"lifecycle,omitempty"`

	// +optional
	Config *TeamConfig `json:"config,omitempty"`
}

// TeamLeadSpec defines the team lead.
type TeamLeadSpec struct {
	// +kubebuilder:validation:Required
	AgentRef ObjectReference `json:"agentRef"`
	Prompt   string          `json:"prompt,omitempty"`
}

// TeamMemberSpec defines a static team member.
type TeamMemberSpec struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
	// +kubebuilder:validation:Required
	AgentRef     ObjectReference `json:"agentRef"`
	Prompt       string          `json:"prompt,omitempty"`
	PlanApproval bool            `json:"planApproval,omitempty"`
}

// DynamicMembersSpec defines the policy for dynamic team membership.
type DynamicMembersSpec struct {
	Enabled bool `json:"enabled"`
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=32
	MaxTotal         int32             `json:"maxTotal,omitempty"`
	AllowedAgentRefs []ObjectReference `json:"allowedAgentRefs,omitempty"`
}

// SharedContextSpec defines shared context accessible to all members.
type SharedContextSpec struct {
	ConfigMapRef string `json:"configMapRef,omitempty"`
}

// RecoverySpec defines fault recovery policy.
type RecoverySpec struct {
	// +kubebuilder:validation:Enum=Auto;Manual;None
	// +kubebuilder:default="Auto"
	ReschedulePolicy string `json:"reschedulePolicy,omitempty"`
	// +kubebuilder:validation:Minimum=0
	// +kubebuilder:validation:Maximum=10
	// +kubebuilder:default=3
	MaxRestarts    int32  `json:"maxRestarts,omitempty"`
	RestartBackoff string `json:"restartBackoff,omitempty"`
	GraceWindow    string `json:"graceWindow,omitempty"`
}

// TeamLifecycle defines team lifecycle limits.
type TeamLifecycle struct {
	MaxDuration       string `json:"maxDuration,omitempty"`
	TTLAfterCompleted string `json:"ttlAfterCompleted,omitempty"`
	TTLAfterFailed    string `json:"ttlAfterFailed,omitempty"`
}

// TeamConfig defines team-level configuration.
type TeamConfig struct {
	// +kubebuilder:validation:Enum=self-claim;lead-assign
	// +kubebuilder:default="self-claim"
	TaskClaimStrategy string `json:"taskClaimStrategy,omitempty"`
	// +kubebuilder:validation:Enum=lead-decides;all-complete;timeout
	// +kubebuilder:default="lead-decides"
	ShutdownPolicy string `json:"shutdownPolicy,omitempty"`
}

// AgentTeamStatus defines the observed state of AgentTeam.
type AgentTeamStatus struct {
	Phase      TeamPhase          `json:"phase,omitempty"`
	StartedAt  string             `json:"startedAt,omitempty"`
	Lead       *TeamMemberStatus  `json:"lead,omitempty"`
	Members    []TeamMemberStatus `json:"members,omitempty"`
	Tasks      *TeamTaskSummary   `json:"tasks,omitempty"`
	Conditions []Condition        `json:"conditions,omitempty"`
}

// TeamMemberStatus represents the runtime state of a team member.
type TeamMemberStatus struct {
	Name              string       `json:"name"`
	Origin            MemberOrigin `json:"origin,omitempty"`
	AgentRef          string       `json:"agentRef,omitempty"`
	SessionID         string       `json:"sessionId,omitempty"`
	InstanceRef       string       `json:"instanceRef,omitempty"`
	Phase             MemberPhase  `json:"phase,omitempty"`
	CurrentTask       string       `json:"currentTask,omitempty"`
	RestartCount      int32        `json:"restartCount,omitempty"`
	LastRestartAt     string       `json:"lastRestartAt,omitempty"`
	LastRestartReason string       `json:"lastRestartReason,omitempty"`
	AddedAt           string       `json:"addedAt,omitempty"`
}

// TeamTaskSummary holds aggregate task counts.
type TeamTaskSummary struct {
	Total      int32 `json:"total"`
	Pending    int32 `json:"pending"`
	InProgress int32 `json:"inProgress"`
	Completed  int32 `json:"completed"`
}
