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
