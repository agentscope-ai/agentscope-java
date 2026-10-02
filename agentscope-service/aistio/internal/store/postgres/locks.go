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

package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type lockRepo struct {
	pool *pgxpool.Pool
}

func intervalSeconds(d time.Duration) string {
	sec := d.Seconds()
	if sec < 0 {
		sec = 0
	}
	return fmt.Sprintf("%f seconds", sec)
}

func (r *lockRepo) Acquire(ctx context.Context, tenant, name, ownerToken, holder string, ttl time.Duration) (*store.Lock, error) {
	lk := &store.Lock{Name: name}
	var holderPtr *string
	err := r.pool.QueryRow(ctx, `
		INSERT INTO dp_locks (tenant, lock_name, owner_token, fencing_token, holder, acquired_at, expires_at)
		VALUES ($1, $2, $3, nextval('dp_lock_fencing_seq'), $4, now(), now() + $5::interval)
		ON CONFLICT (tenant, lock_name) DO UPDATE
		   SET owner_token = EXCLUDED.owner_token,
		       fencing_token = EXCLUDED.fencing_token,
		       holder = EXCLUDED.holder,
		       acquired_at = now(),
		       expires_at = EXCLUDED.expires_at
		 WHERE dp_locks.expires_at <= now()
		RETURNING owner_token, fencing_token, expires_at, holder, lock_name`,
		tenant, name, ownerToken, nullStr(holder), intervalSeconds(ttl),
	).Scan(&lk.OwnerToken, &lk.FencingToken, &lk.ExpiresAt, &holderPtr, &lk.Name)
	if err == nil {
		lk.Holder = deref(holderPtr)
		return lk, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	// Conflict: held by another and not expired.
	cur := &store.Lock{Name: name}
	err = r.pool.QueryRow(ctx, `
		SELECT owner_token, fencing_token, expires_at, holder, lock_name
		FROM dp_locks WHERE tenant=$1 AND lock_name=$2`,
		tenant, name,
	).Scan(&cur.OwnerToken, &cur.FencingToken, &cur.ExpiresAt, &holderPtr, &cur.Name)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	cur.Holder = deref(holderPtr)
	return cur, store.ErrConflict
}

func (r *lockRepo) Renew(ctx context.Context, tenant, name, ownerToken string, ttl time.Duration) (*store.Lock, error) {
	lk := &store.Lock{Name: name}
	var holderPtr *string
	err := r.pool.QueryRow(ctx, `
		UPDATE dp_locks
		SET expires_at = now() + $4::interval
		WHERE tenant=$1 AND lock_name=$2 AND owner_token=$3
		RETURNING owner_token, fencing_token, expires_at, holder, lock_name`,
		tenant, name, ownerToken, intervalSeconds(ttl),
	).Scan(&lk.OwnerToken, &lk.FencingToken, &lk.ExpiresAt, &holderPtr, &lk.Name)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	lk.Holder = deref(holderPtr)
	return lk, nil
}

func (r *lockRepo) Release(ctx context.Context, tenant, name, ownerToken string) error {
	_, err := r.pool.Exec(ctx, `
		DELETE FROM dp_locks WHERE tenant=$1 AND lock_name=$2 AND owner_token=$3`,
		tenant, name, ownerToken)
	return err
}

func (r *lockRepo) Peek(ctx context.Context, tenant, name string) (*store.Lock, error) {
	lk := &store.Lock{Name: name}
	var holderPtr *string
	err := r.pool.QueryRow(ctx, `
		SELECT owner_token, fencing_token, expires_at, holder, lock_name
		FROM dp_locks
		WHERE tenant=$1 AND lock_name=$2 AND expires_at > now()`,
		tenant, name,
	).Scan(&lk.OwnerToken, &lk.FencingToken, &lk.ExpiresAt, &holderPtr, &lk.Name)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	lk.Holder = deref(holderPtr)
	return lk, nil
}

func (r *lockRepo) PurgeExpired(ctx context.Context, olderThan time.Duration) (int64, error) {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM dp_locks WHERE expires_at < now() - $1::interval`,
		intervalSeconds(olderThan))
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}
