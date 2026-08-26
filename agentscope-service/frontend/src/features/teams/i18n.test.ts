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

import { describe, expect, it } from 'vitest';
import type { TranslationFunction } from '@/i18n';
import { translate } from '@/i18n/translate';
import {
  deployModeLabel,
  formatTeamDate,
  formatTeamNumber,
  memberPhaseLabel,
  planStatusLabel,
  teamPhaseLabel,
} from './i18n';

const t: TranslationFunction = (key, params) => translate('en', key, params);

describe('team display localization', () => {
  it('localizes known machine values without changing unknown values', () => {
    expect(teamPhaseLabel(t, 'Running')).toBe('Running');
    expect(memberPhaseLabel(t, 'Shutdown')).toBe('Shutdown');
    expect(deployModeLabel(t, 'byo')).toBe('BYO');
    expect(planStatusLabel(t, 'approved')).toBe('Approved');
    expect(teamPhaseLabel(t, 'Experimental')).toBe('Experimental');
  });

  it('formats display numbers and preserves invalid date strings', () => {
    expect(formatTeamNumber('en', 1234)).toBe('1,234');
    expect(formatTeamDate('zh', 'not-a-date')).toBe('not-a-date');
  });
});
