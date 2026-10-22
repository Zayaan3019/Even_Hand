import { useMemo, useState } from 'react';
import type { Result, TaEstimate } from '../api';
import { Card, Empty, HowToRead, SortHeader } from '../components/ui';
import { SeverityChart } from '../components/charts';
import { useName } from '../names';
import { inMarks, naturalCompare, pValue, signed } from '../format';

type Key = 'label' | 'marks' | 'severity';

export function Tas({ r }: { r: Result }) {
  const name = useName();
  const [sort, setSort] = useState<{ key: Key; dir: 1 | -1 }>({ key: 'severity', dir: -1 });
  const rows = useMemo(() => {
    const list = [...(r.tas ?? [])];
    list.sort((a: TaEstimate, b: TaEstimate) => sort.dir * (sort.key === 'label' ? naturalCompare(name(a.label), name(b.label))
      : (a[sort.key] as number) - (b[sort.key] as number)));
    return list;
  }, [r.tas, sort, name]);
  if (!r.tas) {
    return <div className="panel"><Empty>Severity was not estimated: this arrangement of marking cannot support it.</Empty></div>;
  }
  const onSort = (k: Key) => setSort((s) => ({ key: k, dir: s.key === k ? (-s.dir as 1 | -1) : -1 }));
  const other = new Map(r.crossCheck?.rows.map((x) => [x.label, x.other]) ?? []);

  return (
    <div className="panel">
      <Card title="Each TA's severity">
        <p className="muted small" style={{ marginTop: 0 }}>
          Percentage points of each question's maximum: +5 means about half a mark less on a 10-mark question than the
          average TA would give the same answer. Intervals include an allowance for what the method cannot see.
        </p>
        <HowToRead>
          <b>Severity</b> is how many percentage points of a question's maximum a TA took off (positive) or added (negative)
          compared with the average TA, for the same answers. The <b>95% interval</b> is the range the true figure is very likely
          in; a TA whose interval does not include zero is shown in bold. A hollow ring, where shown, is the second estimate
          below: when the dot and the ring disagree, the TAs' piles of scripts were not alike.
        </HowToRead>
        <SeverityChart otherName={r.crossCheck?.otherName}
                       points={r.tas.map((t) => ({ label: name(t.label), value: t.severity, lo: t.lo, hi: t.hi, other: other.get(t.label) }))} />
        <div className="table-wrap" style={{ marginTop: 12 }}>
          <table className="data">
            <thead>
              <tr>
                <SortHeader k="label" label="TA" sort={sort} onSort={onSort} />
                <SortHeader k="marks" label="Marks written" sort={sort} onSort={onSort} />
                <SortHeader k="severity" label="Severity" sort={sort} onSort={onSort} />
                <th>95% interval</th><th>On a 10-mark question</th><th>Group</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.label}>
                  <td>{name(t.label)}</td>
                  <td className="n">{t.marks}</td>
                  <td className="n">{t.clear ? <b>{signed(t.severity)}</b> : signed(t.severity)}</td>
                  <td className="n">{signed(t.lo)} to {signed(t.hi)}</td>
                  <td>{inMarks(t.severity)}</td>
                  <td className="n">{t.group}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {r.noEffect && (
          <p className="small">
            <b>Could this be chance?</b> If the TAs marked alike, differences this large would arise with probability{' '}
            <b>p = {pValue(r.noEffect.p)}</b>.{' '}
            {r.noEffect.p != null && r.noEffect.p < 0.05 ? 'The differences are unlikely to be chance.'
              : 'The differences are within what chance alone produces.'}
          </p>
        )}
      </Card>

      {r.crossCheck && (
        <Card title="Two independent estimates">
          <p className="muted small" style={{ marginTop: 0 }}>
            {r.basis === 'answers'
              ? 'The figures above come from the answers. The marks alone, which assume every pile was alike, would say:'
              : 'The figures above come from the marks. Comparing answers that say the same thing, independently:'}
          </p>
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>TA</th><th>Used</th><th>{r.crossCheck.otherName}</th><th>Difference</th></tr></thead>
              <tbody>
                {r.crossCheck.rows.map((x) => (
                  <tr key={x.label}>
                    <td>{name(x.label)}</td>
                    <td className="n">{signed(x.used)}</td>
                    <td className="n">{signed(x.other)} <span className="muted">({signed(x.lo)} to {signed(x.hi)})</span></td>
                    <td className="n">{signed(x.used - x.other)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {r.choice && <p className="small"><b>Which estimate corrects the grades.</b> {r.choice}</p>}
        </Card>
      )}
    </div>
  );
}
