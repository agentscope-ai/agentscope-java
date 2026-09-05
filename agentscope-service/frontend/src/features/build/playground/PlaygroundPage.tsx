import { InvocationPlayground } from '@/components/InvocationPlayground';
import { Page, PageHeader } from '@/components/Page';

export default function PlaygroundPage() {
  return <Page>
    <PageHeader title="Playground" description="Test Agents directly, run Team or Workflow jobs, or validate a published Endpoint contract from one workbench." />
    <InvocationPlayground />
  </Page>;
}
