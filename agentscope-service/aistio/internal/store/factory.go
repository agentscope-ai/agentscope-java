package store

import (
	"context"
	"fmt"
)

// Opener opens a Store for a given driver. Sub-packages register themselves
// via RegisterOpener in init().
type Opener func(ctx context.Context, cfg Config) (Store, error)

var openers = map[string]Opener{}

// RegisterOpener registers a driver opener. Called from sub-package init().
func RegisterOpener(driver string, opener Opener) {
	openers[driver] = opener
}

// Open creates a Store for the configured driver and runs Migrate.
func Open(ctx context.Context, cfg Config) (Store, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	opener, ok := openers[cfg.Driver]
	if !ok {
		return nil, fmt.Errorf("store: no opener registered for driver %q (did you import the driver package?)", cfg.Driver)
	}
	s, err := opener(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if err := s.Migrate(ctx); err != nil {
		_ = s.Close()
		return nil, fmt.Errorf("store: migrate: %w", err)
	}
	return s, nil
}
