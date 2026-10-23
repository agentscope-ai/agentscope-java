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
