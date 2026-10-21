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

package adapter

import "fmt"

var registry = map[string]DataPlaneAdapter{}

// Register adds an adapter to the registry.
func Register(adapter DataPlaneAdapter) {
	registry[adapter.RuntimeName()] = adapter
}

// Get returns the adapter for the given runtime name.
func Get(runtime string) (DataPlaneAdapter, error) {
	a, ok := registry[runtime]
	if !ok {
		return nil, fmt.Errorf("no adapter registered for runtime %q", runtime)
	}
	return a, nil
}

// IsRegistered reports whether an adapter is registered for the runtime.
func IsRegistered(runtime string) bool {
	_, ok := registry[runtime]
	return ok
}

// List returns all registered runtime names.
func List() []string {
	names := make([]string, 0, len(registry))
	for name := range registry {
		names = append(names, name)
	}
	return names
}

func init() {
	Register(&AgentScopeJavaAdapter{})
}
