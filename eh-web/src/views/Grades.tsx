import { useMemo, useState } from 'react';
import type { RecheckRow, Result } from '../api';
import { Card, Empty, HowToRead, SortHeader } from '../components/ui';
import { GradeChart } from '../components/charts';
import { useName } from '../names';
import { naturalCompare } from '../format';

type Key = 'student' | 'total' | 'probability' | 'points';

export function Grades({ r }: { r: Result }) {
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
  const onSort = (k: Key) => setSort((s) => ({ key: k, dir: s.key === k ? (-s.dir as 1 | -1) : -1 }));

  return (
    <div className="panel">
      <Card title="What did it do to grades?">
        <p style={{ marginTop: 0 }}>
          <b>{o.share.toFixed(1)}%</b> of the spread in course totals came from who marked the script rather than what was
          written. Correcting for it would change <b>{o.changed}</b> letter grade{o.changed === 1 ? '' : 's'} (90% range {o.low}–{o.high}).
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
            </p>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <SortHeader k="student" label="Student" sort={sort} onSort={onSort} />
                    <SortHeader k="total" label="Total as marked" sort={sort} onSort={onSort} />
                    <th>Corrected</th><th>Grade</th><th>If it changes</th>
                    <SortHeader k="probability" label="Chance of change" sort={sort} onSort={onSort} />
                    <SortHeader k="points" label="Direction" sort={sort} onSort={onSort} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((x) => (
                    <tr key={x.student}>
                      <td>{name(x.student)}</td>
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
