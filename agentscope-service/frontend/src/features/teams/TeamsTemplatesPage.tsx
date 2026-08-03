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
