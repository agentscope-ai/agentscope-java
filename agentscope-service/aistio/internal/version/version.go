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
