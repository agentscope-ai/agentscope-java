-- Copyright 2024-2026 the original author or authors.
-- Licensed under the Apache License, Version 2.0.
ALTER TABLE builder_portal_session ADD COLUMN IF NOT EXISTS budget_limits TEXT NOT NULL DEFAULT '{}';
ALTER TABLE builder_portal_session ADD COLUMN IF NOT EXISTS budget_revision BIGINT NOT NULL DEFAULT 1;
