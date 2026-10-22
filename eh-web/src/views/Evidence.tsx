import { useMemo, useState } from 'react';
import type { PairEvidence, Result } from '../api';
import { Card, Empty, HowToRead } from '../components/ui';
import { useName } from '../names';
import { marks, naturalCompare } from '../format';

export function Evidence({ r }: { r: Result }) {
  const name = useName();
  const c = r.content;
  const [question, setQuestion] = useState('');
  const [ta, setTa] = useState('');
  const all = useMemo(() => [...(c?.sameAnswer ?? []), ...(c?.sameTa ?? [])], [c]);
  const questions = useMemo(() => Array.from(new Set([...all.map((p) => p.question), ...(c?.outOfLine ?? []).map((o) => o.question)]))
    .sort(naturalCompare), [all, c]);
  const tas = useMemo(() => Array.from(new Set([...all.flatMap((p) => [p.first.ta, p.second.ta]), ...(c?.outOfLine ?? []).map((o) => o.ta)]))
    .sort(naturalCompare), [all, c]);
  if (!c) return <div className="panel"><Empty>This file has no written answers to show.</Empty></div>;
  const keep = (p: PairEvidence) => (!question || p.question === question) && (!ta || p.first.ta === ta || p.second.ta === ta);
  const same = c.sameAnswer.filter(keep);
  const inconsistent = c.sameTa.filter(keep);
  const outOfLine = c.outOfLine.filter((o) => (!question || o.question === question) && (!ta || o.ta === ta));
  const nothing = c.sameAnswer.length + c.sameTa.length + c.outOfLine.length === 0;

  return (
    <div className="panel">
      {nothing ? (
        <Empty>No pair of near-identical answers received clearly different marks, and no mark is far out of line with similar answers.</Empty>
      ) : (
        <div className="filters">
          <label className="small muted" htmlFor="fq">Question</label>
          <select id="fq" className="input" value={question} onChange={(e) => setQuestion(e.target.value)}>
            <option value="">All questions</option>
            {questions.map((q) => <option key={q} value={q}>{q}</option>)}
          </select>
          <label className="small muted" htmlFor="ft">TA</label>
          <select id="ft" className="input" value={ta} onChange={(e) => setTa(e.target.value)}>
            <option value="">All TAs</option>
            {tas.map((t) => <option key={t} value={t}>{name(t)}</option>)}
          </select>
        </div>
      )}
      {c.sameAnswer.length > 0 && (
        <Card title={`Nearly the same answer, different marks, different TAs (${same.length})`}>
          <HowToRead>
            Each card shows two students' answers to the same question that say nearly the same thing ("alike" is how close
            they are in meaning), with the marks two different TAs gave. These are the most direct evidence of a marking
            difference, and the quickest to verify: read the two answers side by side.
          </HowToRead>
          <Pairs list={same} />
        </Card>
      )}
      {c.sameTa.length > 0 && (
        <Card title={`Nearly the same answer, different marks, same TA (${inconsistent.length})`}>
          <p className="muted small" style={{ marginTop: 0 }}>Not severity, which would affect both alike, but inconsistency: worth
            discussing the rubric with the TA.</p>
          <Pairs list={inconsistent} />
        </Card>
      )}
      {c.outOfLine.length > 0 && (
        <Card title={`Marks out of line with similar answers (${outOfLine.length})`}>
          <p className="muted small" style={{ marginTop: 0 }}>Each mark is far from what the most similar answers received once each TA's
            severity is allowed for: possibly a slip, a page missed, or an answer the content model misreads. Check the script;
            do not correct blindly.</p>
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Question</th><th>Student</th><th>TA</th><th>Mark</th><th>Similar answers got</th><th>Answer</th></tr></thead>
              <tbody>
                {outOfLine.map((o, i) => (
                  <tr key={i}>
                    <td>{o.question}</td><td>{name(o.student)}</td><td>{name(o.ta)}</td>
                    <td className="n"><b>{marks(o.marks)}</b> / {marks(o.max)}</td>
                    <td className="n">about {marks(Math.round(o.expected * 2) / 2)}</td>
                    <td className="excerpt">{o.excerpt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}

function Pairs({ list }: { list: PairEvidence[] }) {
  const name = useName();
  if (list.length === 0) return <p className="muted">None for this filter.</p>;
  return (
    <div className="pairs">
      {list.map((p, i) => (
        <article className="pair" key={i}>
          <div className="head">
            <span><b>{p.question}</b> · {Math.round(100 * p.similarity)}% alike</span>
            <span>{marks(Math.round((p.gap * p.max) / 100 * 2) / 2)} marks apart</span>
          </div>
          <div className="sides">
            {[{ s: p.first, cls: 'hi' }, { s: p.second, cls: 'lo' }].map(({ s, cls }) => (
              <div className={`side ${cls}`} key={cls}>
                <div className="who"><span className="mk">{marks(s.marks)}</span> / {marks(p.max)} · {name(s.student)} · marked by <b>{name(s.ta)}</b></div>
                <div className="excerpt">{s.excerpt}</div>
              </div>
            ))}
          </div>
        </article>
      ))}
    </div>
  );
}
