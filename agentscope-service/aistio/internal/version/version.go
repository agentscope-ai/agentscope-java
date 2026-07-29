// Package version centralizes build/version information for the control plane.
package version

// These values are the single source of truth for version strings across the
// control plane (REST API, ASDP handshake, etc). They can be overridden at
// build time via -ldflags.
var (
	// Version is the control plane release version.
	Version = "0.2.0"
	// APIVersion is the served CRD API version.
	APIVersion = "v1alpha1"
	// Component is the control plane component name.
	Component = "aistio"
)
