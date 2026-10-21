-- +migrate Up
-- Endpoint Jobs use the Issue collaboration substrate without appearing as
-- ordinary human-authored work in the Work Hub.

ALTER TABLE issues ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'user_work';
ALTER TABLE issues ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'work_hub';
ALTER TABLE issues ADD COLUMN IF NOT EXISTS completion_policy TEXT NOT NULL DEFAULT 'review';

CREATE INDEX IF NOT EXISTS idx_issues_visibility_updated
    ON issues(tenant, namespace, visibility, updated_at DESC)
    WHERE archived_at IS NULL;
