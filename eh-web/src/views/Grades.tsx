import { useContext, useMemo, useState } from 'react';
import type { RecheckRow, Result } from '../api';
import { Card, Empty, HowToRead, SortHeader } from '../components/ui';
import { GradeChart } from '../components/charts';
import { NamesContext, useName } from '../names';
import { fileStem, naturalCompare, saveFile, toCsv } from '../format';
import { taDifferenceShown } from './Overview';

type Key = 'student' | 'total' | 'probability' | 'points';

export function Grades({ r }: { r: Result }) {
  const { show } = useContext(NamesContext);
  const name = useName();
  const o = r.outcome;
  const [sort, setSort] = useState<{ key: Key; dir: 1 | -1 }>({ key: 'points', dir: -1 });
  const rows = useMemo(() => {
    const list = [...(o?.recheck ?? [])];
    list.sort((a: RecheckRow, b: RecheckRow) => sort.dir * (sort.key === 'student' ? naturalCompare(name(a.student), name(b.student))
      : sort.key === 'points' ? Math.abs(a.points) - Math.abs(b.points) : (a[sort.key] as number) - (b[sort.key] as number)));
    return list;
  }, [o, sort, name]);
  if (!o) return <div className="panel"><Empty>No grades were recomputed: the marking arrangement cannot support a severity estimate.</Empty></div>;
  const saveList = () => saveFile(toCsv([
    ['Student', 'Marked by', 'Total as marked', 'Corrected total', 'Grade as marked', 'Grade if it changes', 'Chance of change (%)',
      'Expected change (grade points)', 'Direction'],
    ...rows.map((x) => [name(x.student), (x.markedBy ?? []).map(name).join(', '), x.total.toFixed(1), x.corrected.toFixed(1), x.grade, x.likely,
      Math.round(100 * x.probability), Math.abs(x.points).toFixed(2), x.points > 0 ? 'probably marked down' : 'probably marked up']),
  ]), `${fileStem(r.course.name)}-re-check-list.csv`, 'text/csv;charset=utf-8');
  const onSort = (k: Key) => setSort((s) => ({ key: k, dir: s.key === k ? (-s.dir as 1 | -1) : -1 }));

  return (
    <div className="panel">
      <Card title="What did it do to grades?">
        {taDifferenceShown(r) ? (
          <p style={{ marginTop: 0 }}>
            <b>{o.share.toFixed(1)}%</b> of the spread in course totals came from who marked the script rather than what was
            written. Correcting for it would change <b>{o.changed}</b> letter grade{o.changed === 1 ? '' : 's'} (90% range {o.low}–{o.high}).
          </p>
        ) : (
          <p style={{ marginTop: 0 }}>The TAs' differences are within what chance alone produces, so no grade is attributed to them.</p>
        )}
        <p className="muted small">
          This counts only consistent differences between TAs. One-off disagreements on individual answers also move grades, and on
          real double-graded answers they move more; only double-marking a sample can measure them.
        </p>
        <HowToRead>
          Grey bars are the letter grades as marked. Red bars are the grades the course's own rule would give if every script had
          been marked like the average TA. Where they differ, who marked a script decided some students' grades. No grade is
          changed here: the list below says which scripts to re-mark.
        </HowToRead>
        <GradeChart grades={o.grades} />
      </Card>
      <Card title="Scripts worth a second look">
        {o.refused ? (
          <p className="muted">No list: {o.refused}</p>
        ) : rows.length === 0 ? (
          <p className="muted">The TAs differ, but no student's letter grade is likely to depend on it.</p>
        ) : (
          <>
            <p className="muted small" style={{ marginTop: 0 }}>
              Ranked by expected change in grade points. Re-mark these scripts; Even Hand never changes a grade. Expect about{' '}
              {(o.expectedFalseFlags ?? 0).toFixed(1)} of these {rows.length} to turn out unchanged: the price of catching the others.
              {' '}When grading is relative, the cut-offs are worked out again from the corrected totals, so a grade can change even
              where a script's own total barely moves.
            </p>
            <p className="small">
              <button className="btn small" onClick={saveList}>Save re-check list (CSV)</button>{' '}
              <span className="muted">
                {show ? 'With real roll numbers, as on screen: keep it within the course team.'
                  : 'With pseudonyms, as on screen. Switch on roll numbers above to save them instead.'}
              </span>
            </p>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <SortHeader k="student" label="Student" sort={sort} onSort={onSort} />
                    <th>Marked by</th>
                    <SortHeader k="total" label="Total as marked" sort={sort} onSort={onSort} />
                    <th>Corrected</th><th>Grade</th><th>If it changes</th>
                    <SortHeader k="probability" label="Chance of change" sort={sort} onSort={onSort} />
                    <SortHeader k="points" label="Expected change" sort={sort} onSort={onSort} />
                    <th>Direction</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((x) => (
                    <tr key={x.student}>
                      <td>{name(x.student)}</td>
                      <td>{(x.markedBy ?? []).map(name).join(', ')}</td>
                      <td className="n">{x.total.toFixed(1)}</td>
                      <td className="n">{x.corrected.toFixed(1)}</td>
                      <td>{x.grade}</td>
                      <td>{x.likely}</td>
                      <td>
                        <div className="bar-cell">
                          <div className="b" style={{ width: `${Math.round(60 * x.probability)}px` }} />
                          <span className="n">{Math.round(100 * x.probability)}%</span>
                        </div>
                      </td>
                      <td className="n">{Math.abs(x.points).toFixed(2)} grade points</td>
                      <td>{x.points > 0 ? 'probably marked down' : 'probably marked up'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
