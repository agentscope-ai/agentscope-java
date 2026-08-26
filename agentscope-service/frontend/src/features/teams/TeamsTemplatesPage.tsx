/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Page, PageHeader } from '@/components/Page';

/** Placeholder for reusable team blueprints (P5 stub). */
export default function TeamsTemplatesPage() {
  return (
    <Page>
      <PageHeader
        title="Templates"
        description="Reusable team blueprints (roster + objective presets) will land here. Instantiation will still POST /api/v1/teams."
        actions={
          <Button asChild>
            <Link to="/teams/new">Create team now</Link>
          </Button>
        }
      />
      <div className="rounded-xl border border-dashed border-border bg-white px-6 py-16 text-center">
        <p className="text-sm text-muted-foreground">
          Templates are not implemented in this release. Use New team to start a live store-backed
          team.
        </p>
      </div>
    </Page>
  );
}
