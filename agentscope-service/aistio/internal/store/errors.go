package store

import "errors"

var (
	// ErrNotFound is returned when a requested entity does not exist.
	ErrNotFound = errors.New("store: not found")

	// ErrConflict is returned on optimistic-lock / unique-constraint conflicts
	// (e.g. TeamTask.Claim with a stale expectedVersion).
	ErrConflict = errors.New("store: conflict")
)
