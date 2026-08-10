// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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
