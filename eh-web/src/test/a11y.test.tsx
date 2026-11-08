import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import axe from 'axe-core';
import { NamesContext } from '../names';
import { Overview } from '../views/Overview';
import { Tas } from '../views/Tas';
import { Why } from '../views/Why';
import { Evidence } from '../views/Evidence';
import { Grades } from '../views/Grades';
import { Details } from '../views/Details';
import { ReviewStep, UploadStep } from '../views/Wizard';
import { fixture } from './fixture';

/**
 * Every view passes axe's rules for names, roles, ARIA, tables, forms and keyboard access. Colour contrast needs a real
 * browser, so it is checked separately in Chrome; landmark rules apply to the whole page, which App provides.
 */
async function violations(node: ReactNode): Promise<string[]> {
  const { container } = render(<NamesContext.Provider value={{ show: true, table: fixture().names }}>{node}</NamesContext.Provider>);
  const result = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false }, 'landmark-one-main': { enabled: false },
      'page-has-heading-one': { enabled: false } },
  });
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`);
}

describe('every view can be used with a screen reader and a keyboard', () => {
  const info = { rows: 10, students: 5, assistants: 2, questions: 2, answers: 10, keys: 0,
    assessments: ['Quiz 1', 'End-sem'], findings: [] };
  const views: [string, () => ReactNode][] = [
    ['upload', () => <UploadStep onFile={() => {}} problem="That file is empty." busy={false} done={false} />],
    ['review', () => <ReviewStep fileName="marks.csv" info={info} status={null} busy={false} done={false} onRun={() => {}} />],
    ['overview', () => <Overview r={fixture()} go={() => {}} />],
    ['TAs', () => <Tas r={fixture()} />],
    ['why', () => <Why r={fixture()} />],
    ['evidence', () => <Evidence r={fixture()} />],
    ['grades', () => <Grades r={fixture()} />],
    ['details', () => <Details r={fixture()} />],
  ];
  for (const [name, view] of views) {
    it(name, async () => {
      expect(await violations(view())).toEqual([]);
    });
  }
});
