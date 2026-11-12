// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

const schedulerSchemaSQL = `
CREATE TABLE scheduler_jobs (
 id TEXT PRIMARY KEY, kind TEXT NOT NULL, channel_id TEXT,
 payload JSONB NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
 attempts INT NOT NULL DEFAULT 0, next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_token TEXT NOT NULL DEFAULT '', lease_until TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX scheduler_jobs_pending ON scheduler_jobs(next_attempt,id) WHERE state='pending';
CREATE INDEX scheduler_jobs_channel ON scheduler_jobs(channel_id,created_at) WHERE channel_id IS NOT NULL;
CREATE TABLE scheduler_channel_throttle (
 peer_key TEXT PRIMARY KEY,
 channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 events TIMESTAMPTZ[] NOT NULL DEFAULT '{}',
 blocked_until TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE scheduler_stream_leases (
 channel_id TEXT PRIMARY KEY REFERENCES channels(channel_id) ON DELETE CASCADE,
 token TEXT NOT NULL, lease_until TIMESTAMPTZ NOT NULL
);
`
