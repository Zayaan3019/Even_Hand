import type { Result } from '../api';
import { Card, Empty } from '../components/ui';
import { pct } from '../format';

export function Details({ r }: { r: Result }) {
  return (
    <div className="panel">
      <Card title="What reading the file and auditing the marking found">
        {r.diagnostics.length === 0 ? <Empty>Nothing to report.</Empty> : (
          <ul className="findings">
            {r.diagnostics.map((f, i) => (
              <li key={i} className={f.severity}>
                <span className="tag">{f.severity === 'error' ? 'Problem' : f.severity === 'warning' ? 'Check' : 'Note'}</span>
                {f.message}{f.more > 0 && <i> (and {f.more} more like it)</i>}
                {f.fix && <span className="fix">How to fix: {f.fix}</span>}
              </li>
            ))}
          </ul>
        )}
      </Card>
      {r.content && r.content.questions.length > 0 && (
        <Card title="The answers, question by question">
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Question</th><th>Written answers</th><th>TAs</th><th>Matched pairs</th><th>Content signal</th></tr></thead>
              <tbody>
                {r.content.questions.map((q) => (
                  <tr key={q.label}>
                    <td>{q.label}</td><td className="n">{q.answers}</td><td className="n">{q.tas}</td>
                    <td className="n">{q.pairs}</td><td className="n">{pct(Math.max(0, q.signal))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">Content signal: the cross-validated share of how marks vary within a TA's pile that what the
            answer says explains.</p>
        </Card>
      )}
      <Card title="Inputs, and how to reproduce this analysis">
        <ul className="small">
          {r.footnotes.map((f, i) => <li key={i}>{f}</li>)}
        </ul>
        <p className="muted small">No grade has been changed by this tool. Severity describes a marking session, not a person.
          Everything ran on this computer; nothing left it.</p>
      </Card>
    </div>
  );
}
