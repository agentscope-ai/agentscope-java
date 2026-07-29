package controller

// ConfigDistributor pushes config updates to connected data plane instances.
// Nil-safe: callers check for nil before calling.
type ConfigDistributor interface {
	PushConfig(namespace, agentName string, configType int32, resources interface{}) error
	// ForgetAgent drops all cached config snapshots for a deleted agent so that
	// versions/nonces do not leak or get reused for a recreated agent.
	ForgetAgent(namespace, agentName string)
}

// Config type constants matching asdp.ConfigType enum values.
// Duplicated here to keep the controller package decoupled from asdp.
const (
	DistConfigAgent    int32 = 1
	DistConfigTool     int32 = 2
	DistConfigSkill    int32 = 3
	DistConfigOverride int32 = 4
	DistConfigModel    int32 = 5
)
