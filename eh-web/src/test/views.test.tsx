import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { NamesContext } from '../names';
import { Overview } from '../views/Overview';
import { Tas } from '../views/Tas';
import { Why } from '../views/Why';
import { Evidence } from '../views/Evidence';
import { Grades } from '../views/Grades';
import { ReviewStep } from '../views/Wizard';
import { fixture } from './fixture';

function withNamesOn(show: boolean, node: ReactNode) {
  return <NamesContext.Provider value={{ show, table: fixture().names }}>{node}</NamesContext.Provider>;
}

describe('the dashboard shows what the server computed', () => {
  it('overview: bottom line with names substituted on screen, verdict, and the headline figures', () => {
    render(withNamesOn(true, <Overview r={fixture()} go={() => {}} />));
    expect(screen.getByText(/Ravi was the harshest/)).toBeTruthy();
    expect(screen.getByText('Checkable through the answers')).toBeTruthy();
    expect(screen.getByText('1.0')).toBeTruthy(); // 10.4 points between the TAs, as marks on a 10-mark question
    expect(screen.getAllByText('40%').length).toBeGreaterThan(0);
    expect(screen.getByRole('img', { name: /Severity of each TA/ })).toBeTruthy();
  });

  it('keeps pseudonyms when names are switched off', () => {
    render(withNamesOn(false, <Overview r={fixture()} go={() => {}} />));
    expect(screen.getByText(/Assistant 1 was the harshest/)).toBeTruthy();
    expect(screen.queryByText(/Ravi/)).toBeNull();
  });

  it('TAs: each TA with interval and the second, independent estimate', () => {
    render(withNamesOn(true, <Tas r={fixture()} />));
    const table = screen.getAllByRole('table')[0];
    expect(within(table).getByText('Ravi')).toBeTruthy();
    expect(within(table).getByText('+2.1 to +8.3')).toBeTruthy();
    expect(screen.getByText('From the marks alone (assumes piles were alike)')).toBeTruthy();
    expect(screen.getByText(/Hausman test p = 0.004/)).toBeTruthy();
  });

  it('why: decomposition, mechanisms, the causal checks and Rosenbaum sensitivity', () => {
    render(withNamesOn(true, <Why r={fixture()} />));
    expect(screen.getAllByText('Consistent severity')).toHaveLength(2); // the line up front and the explanation
    expect(screen.getByText('Placebo')).toBeTruthy();
    expect(screen.getByText('Negative control: answer length')).toBeTruthy();
    expect(screen.getByText('2.4')).toBeTruthy();
    expect(screen.getByRole('img', { name: /split into its causes/ })).toBeTruthy();
  });

  it('why: one line per TA up front, and many questions as rows, not columns', () => {
    const r = fixture();
    const byQuestion = Array.from({ length: 30 }, (_, i) => ({ question: `Q${i + 1}`, severity: i % 7, se: 1, pairs: 20 }));
    r.content!.causal!.mechanisms[0].byQuestion = byQuestion;
    r.content!.causal!.mechanisms[1].byQuestion = byQuestion.map((q) => ({ ...q, severity: -q.severity }));
    render(withNamesOn(true, <Why r={r} />));
    const lines = screen.getAllByRole('listitem');
    expect(lines).toHaveLength(2);
    expect(within(lines[0]).getByText('+5.2')).toBeTruthy();
    expect(within(lines[0]).getByText(/Γ = 2.4/)).toBeTruthy();
    const heat = screen.getByRole('table', { name: /each question/ });
    expect(within(heat).getAllByRole('columnheader')).toHaveLength(3); // the question, then one column per TA
    expect(within(heat).getAllByRole('rowheader')).toHaveLength(10); // the ten largest first
    fireEvent.click(screen.getByRole('button', { name: 'Show all 30 questions' }));
    expect(within(heat).getAllByRole('rowheader')).toHaveLength(30);
  });

  it('why: without written answers, it says what to add instead of showing empty charts', () => {
    const r = fixture();
    delete r.content;
    render(withNamesOn(true, <Why r={r} />));
    expect(screen.getByText(/no written answers/)).toBeTruthy();
  });

  it('evidence: near-identical answers side by side, and marks out of line', () => {
    render(withNamesOn(true, <Evidence r={fixture()} />));
    expect(screen.getByText('The moment is maximum where shear is zero.')).toBeTruthy();
    expect(screen.getByText(/96% alike/)).toBeTruthy();
    expect(screen.getByText('CE23B009')).toBeTruthy();
  });

  it('grades: the re-check list with its chance of change', () => {
    render(withNamesOn(false, <Grades r={fixture()} />));
    expect(screen.getByText('Student 7')).toBeTruthy();
    expect(screen.getByText('80%')).toBeTruthy();
    expect(screen.getByText('probably marked down')).toBeTruthy();
  });
});

describe('the review step', () => {
  it("asks for one weight per assessment and says when there are no answers", () => {
    const info = { rows: 10, students: 5, assistants: 2, questions: 2, answers: 0, keys: 0,
      assessments: ['Quiz 1', 'End-sem'], findings: [] };
    render(<ReviewStep fileName="marks.csv" info={info} status={null} busy={false} done={false} onRun={() => {}} />);
    expect(screen.getByLabelText('Weight of Quiz 1')).toBeTruthy();
    expect(screen.getByText(/no written answers/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Check the marking/ })).toHaveProperty('disabled', false);
  });
});
