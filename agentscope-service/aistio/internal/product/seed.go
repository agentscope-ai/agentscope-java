package product

import (
	"context"
	"log"
)

func seedUsers(ctx context.Context, db *DB) error {
	var n int
	if err := db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM users`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}

	seeds := []struct {
		id, username, password, roles string
	}{
		{"admin", "admin", "admin", "user,admin"},
		{"bob", "bob", "bob", "user"},
		{"alice", "alice", "alice", "user"},
	}
	now := nowMillis()
	for _, u := range seeds {
		hash, err := hashPassword(u.password)
		if err != nil {
			return err
		}
		_, err = db.Pool.Exec(ctx,
			`INSERT INTO users (user_id, username, password_hash, roles_csv, created_at)
			 VALUES ($1,$2,$3,$4,$5) ON CONFLICT (user_id) DO NOTHING`,
			u.id, u.username, hash, u.roles, now)
		if err != nil {
			return err
		}
		log.Printf("seeded user %s/%s roles=%s", u.username, u.password, u.roles)
	}
	return nil
}
