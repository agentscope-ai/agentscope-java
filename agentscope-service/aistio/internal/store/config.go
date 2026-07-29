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
