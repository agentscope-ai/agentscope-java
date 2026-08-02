package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type snapshotRepo struct {
	pool *pgxpool.Pool
}

func (r *snapshotRepo) Put(ctx context.Context, tenant, snapshotID string, payload []byte, mode string) (*store.SnapshotMeta, error) {
	if mode == "" {
		mode = store.SnapshotModeInline
	}
	now := time.Now().UTC()
	meta := &store.SnapshotMeta{}
	var extURL *string
	err := r.pool.QueryRow(ctx, `
		INSERT INTO dp_snapshots (tenant, snapshot_id, size_bytes, storage_mode, payload, created_at, accessed_at)
		VALUES ($1, $2, $3, $4, $5, $6, $6)
		ON CONFLICT (tenant, snapshot_id) DO UPDATE
		   SET size_bytes = EXCLUDED.size_bytes,
		       storage_mode = EXCLUDED.storage_mode,
		       payload = EXCLUDED.payload,
		       accessed_at = EXCLUDED.accessed_at
		RETURNING snapshot_id, size_bytes, storage_mode, external_url, created_at, accessed_at`,
		tenant, snapshotID, int64(len(payload)), mode, payload, now,
	).Scan(&meta.SnapshotID, &meta.SizeBytes, &meta.StorageMode, &extURL, &meta.CreatedAt, &meta.AccessedAt)
	if err != nil {
		return nil, err
	}
	meta.ExternalURL = deref(extURL)
	return meta, nil
}

func (r *snapshotRepo) Get(ctx context.Context, tenant, snapshotID string) ([]byte, *store.SnapshotMeta, error) {
	meta := &store.SnapshotMeta{}
	var payload []byte
	var extURL *string
	err := r.pool.QueryRow(ctx, `
		UPDATE dp_snapshots SET accessed_at=now()
		WHERE tenant=$1 AND snapshot_id=$2
		RETURNING snapshot_id, size_bytes, storage_mode, external_url, created_at, accessed_at, payload`,
		tenant, snapshotID,
	).Scan(&meta.SnapshotID, &meta.SizeBytes, &meta.StorageMode, &extURL, &meta.CreatedAt, &meta.AccessedAt, &payload)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, store.ErrNotFound
		}
		return nil, nil, err
	}
	meta.ExternalURL = deref(extURL)
	return payload, meta, nil
}

func (r *snapshotRepo) Exists(ctx context.Context, tenant, snapshotID string) (bool, error) {
	var exists bool
	err := r.pool.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM dp_snapshots WHERE tenant=$1 AND snapshot_id=$2)`,
		tenant, snapshotID,
	).Scan(&exists)
	return exists, err
}

func (r *snapshotRepo) Touch(ctx context.Context, tenant, snapshotID string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE dp_snapshots SET accessed_at=now() WHERE tenant=$1 AND snapshot_id=$2`,
		tenant, snapshotID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}
