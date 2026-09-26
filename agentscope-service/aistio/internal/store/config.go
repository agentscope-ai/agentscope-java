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

package store

import (
	"fmt"
	"time"
)

// Driver names.
const (
	DriverMemory   = "memory"
	DriverPostgres = "postgres"
)

// Config holds store configuration.
type Config struct {
	Driver string

	// Postgres DSN, e.g. postgres://user:pass@host:5432/aistio?sslmode=require
	PostgresDSN string

	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration

	Retention RetentionConfig
}

// DefaultConfig returns a memory-driver config suitable for local/dev.
func DefaultConfig() Config {
	return Config{
		Driver:          DriverMemory,
		MaxOpenConns:    20,
		MaxIdleConns:    5,
		ConnMaxLifetime: 30 * time.Minute,
		Retention:       DefaultRetention(),
	}
}

// Validate checks the config for consistency.
func (c Config) Validate() error {
	switch c.Driver {
	case DriverMemory:
		return nil
	case DriverPostgres:
		if c.PostgresDSN == "" {
			return fmt.Errorf("store: postgres driver requires a DSN")
		}
		return nil
	case "":
		return fmt.Errorf("store: driver is required")
	default:
		return fmt.Errorf("store: unsupported driver %q (want memory|postgres)", c.Driver)
	}
}
