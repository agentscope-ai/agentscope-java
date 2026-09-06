export interface NamedResourceOption {
  id: string;
  label: string;
  secondary?: string;
}

export function filterNamedResourceOptions(options: NamedResourceOption[], search: string) {
  const needle = search.trim().toLowerCase();
  return options
    .filter(option => !needle || [option.label, option.secondary, option.id]
      .filter(Boolean).join(' ').toLowerCase().includes(needle))
    .sort((left, right) => left.label.localeCompare(right.label));
}
