import { describe, expect, it } from 'vitest';
import { filterNamedResourceOptions, type NamedResourceOption } from './namedResourceOptions';

const options: NamedResourceOption[] = [
  { id: 'team-2', label: 'Release Team', secondary: 'Team' },
  { id: 'workflow-1', label: 'Incident Review', secondary: 'Workflow' },
  { id: 'team-1', label: 'Billing Team', secondary: 'Team' },
];

describe('filterNamedResourceOptions', () => {
  it('sorts resource names for predictable dropdowns', () => {
    expect(filterNamedResourceOptions(options, '').map(option => option.label))
      .toEqual(['Billing Team', 'Incident Review', 'Release Team']);
  });

  it('searches name, type label, and stable ID', () => {
    expect(filterNamedResourceOptions(options, 'workflow').map(option => option.id))
      .toEqual(['workflow-1']);
    expect(filterNamedResourceOptions(options, 'team-2').map(option => option.label))
      .toEqual(['Release Team']);
  });
});
