import type { Causal, MechanismKind, Result } from '../api';
import { Badge, Card, Empty, HowToRead } from '../components/ui';
import { DecompositionChart, QuestionHeatmap, VarianceBar } from '../components/charts';
import { useName, useNamedText } from '../names';
import { inMarks, pct, pValue, signed } from '../format';

const KIND: Record<MechanismKind, { text: string; tone: 'red' | 'amber' | 'green' }> = {
  CONSISTENT_SEVERITY: { text: 'Consistent severity', tone: 'red' },
  QUESTION_SPECIFIC: { text: 'Rubric difference', tone: 'red' },
  DRIFT: { text: 'Drift', tone: 'amber' },
  ISOLATED_SLIPS: { text: 'Isolated slips', tone: 'amber' },
  NO_CLEAR_DIFFERENCE: { text: 'No clear difference', tone: 'green' },
};

export function Why({ r }: { r: Result }) {
  const causal = r.content?.causal;
  if (!r.content) {
    return (
      <div className="panel">
        <Empty>
          This file has marks but no written answers, so Even Hand can say <i>that</i> the TAs differed but not whether the
          answers explain it. Add each answer's text (a column such as <b>Q1 answer</b>) and run it again.
        </Empty>
      </div>
    );
  }
  if (!causal) {
    return (
      <div className="panel">
        <Card title="Why did the marks differ?">
          {r.content.notes.map((n, i) => <p key={i} className="muted">{n}</p>)}
        </Card>
      </div>
    );
  }
  return <CausalView causal={causal} r={r} />;
}

interface CheckRow { name: string; ok: boolean; q: string; res: string }

/** The fairness checks on the comparison, in the order the report explains them. */
function causalChecks(causal: Causal, real: boolean): CheckRow[] {
  const minShare = Math.min(...causal.overlap.map((o) => o.matchedShare));
  const worstAfter = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.after ?? 0)));
  const worstBefore = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.before)));
  const worstLength = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.length ?? 0)));
  const checks: CheckRow[] = [
    { name: 'Comparable answers exist', ok: minShare >= 0.5,
      q: 'Does every TA have answers that closely match answers marked by others?',
      res: `At least ${pct(minShare)} of each TA's answers have a close match.` },
    { name: 'Like is compared with like', ok: worstAfter < 0.25,
      q: 'Are matched answers about equally good? Under 0.25 passes; the content correction removes the rest.',
      res: `${worstAfter.toFixed(2)} after matching, ${worstBefore.toFixed(2)} between whole piles.` },
    { name: 'Negative control: answer length', ok: worstLength < 0.25,
      q: 'A TA cannot change how much a student wrote, so matched answers should not differ in length.',
      res: `${worstLength.toFixed(2)}; under 0.25 passes.` },
    { name: 'Placebo', ok: !real || causal.placebo.p < 0.05,
      q: 'With TA labels shuffled among similar answers, does the method still find differences this large?',
      res: `p = ${pValue(causal.placebo.p)}: a spread of ${causal.placebo.observed.toFixed(1)} observed, `
        + `${causal.placebo.shuffled.toFixed(1)} when shuffled.` },
  ];
  const twice = causal.doubleMarked;
  if (twice) {
    checks.push({ name: 'Same script, two TAs', ok: twice.agreement == null || twice.agreement >= 0.7,
      q: 'Scripts marked twice compare TAs on identical content: do they agree with the matched-answer estimate?',
      res: `${twice.scripts} scripts; correlation ${twice.agreement == null ? 'n/a' : twice.agreement.toFixed(2)}.` });
  }
  return checks;
}

function CausalView({ causal, r }: { causal: Causal; r: Result }) {
  const name = useName();
  const named = useNamedText();
  const c = r.content!;
  const real = r.noEffect?.p != null && r.noEffect.p < 0.05;
  const checks = causalChecks(causal, real);
  const passed = checks.filter((k) => k.ok).length;
  const estimate = new Map((r.tas ?? []).map((t) => [t.label, t]));
  const gamma = new Map(causal.sensitivity.map((s) => [s.ta, s.gamma]));
  const questions = causal.mechanisms.flatMap((m) => m.byQuestion.map((q) => q.question));

  return (
    <div className="panel">
      <Card title="Why did the marks differ?">
        <p style={{ marginTop: 0 }}>
          A gap between two TAs' piles has two possible causes: one pile's students wrote better answers (legitimate), or
          the TA marked the same work differently (a marking effect). Even Hand compares <b>answers that say the same
          thing</b>, so whatever difference remains was caused by who marked them.
        </p>
        <div className="verdicts" role="list">
          {causal.mechanisms.map((m) => {
            const t = estimate.get(m.ta);
            const g = gamma.get(m.ta);
            return (
              <div className="verdict-row" role="listitem" key={m.ta}>
                <div className="who"><b>{name(m.ta)}</b><Badge tone={KIND[m.kind].tone}>{KIND[m.kind].text}</Badge></div>
                <div className="effect">
                  {t ? (
                    <>
                      <span className="fig">{signed(t.severity)}</span> points
                      <span className="muted"> · {t.clear ? `${inMarks(t.severity)} on a 10-mark question` : 'within chance'}</span>
                    </>
                  ) : <span className="muted">–</span>}
                </div>
                <div className="robust muted">
                  {g == null ? '' : g > 1 ? `robust to hidden bias up to Γ = ${g.toFixed(1)}` : 'not conclusive on its own'}
                </div>
              </div>
            );
          })}
        </div>
        <p className={`notice ${passed === checks.length ? 'good' : 'warn'}`}>
          {passed} of {checks.length} checks that the comparison is fair passed
          {passed === checks.length ? '.' : '; see the last section for which to read with care.'}
        </p>
        <h4>What the variation in marks is made of</h4>
        <VarianceBar content={causal.variance.content} ta={causal.variance.ta} residual={causal.variance.residual} />
        <p className="muted small">
          Answers read by {c.embedder}. {pct(c.textCoverage)} of responses carry text; what an answer says explains{' '}
          {pct(c.signal)} of how its mark varies within a TA's pile; {pct(c.matchedShare)} of answers have a close match
          marked by another TA.
        </p>
        {c.notes.map((n, i) => <p key={i} className="muted small">{n}</p>)}
      </Card>

      <Card title="Each TA's difference, split into its causes">
        <HowToRead>
          Take one TA's pile. The black line says how much higher (right) or lower (left) its marks were than the class's on the
          same questions, in points of each question's maximum. The green part is how much better or worse that pile's answers
          were: a legitimate reason. The red part is the TA's own marking: the part worth correcting. The grey part is what
          neither explains, such as chance.
        </HowToRead>
        <DecompositionChart rows={causal.decomposition.map((d) => ({
          label: name(d.ta), raw: d.raw, content: d.content, marking: d.marking, unexplained: d.unexplained,
        }))} />
        <details className="more">
          <summary>Show the numbers</summary>
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>TA</th><th>Answers</th><th>Pile vs class</th><th>What students wrote</th><th>How it was marked</th><th>Neither</th></tr></thead>
              <tbody>
                {causal.decomposition.map((d) => (
                  <tr key={d.ta}>
                    <td>{name(d.ta)}</td><td className="n">{d.answers}</td><td className="n"><b>{signed(d.raw)}</b></td>
                    <td className="n">{signed(d.content)}</td><td className="n">{signed(d.marking)}</td><td className="n">{signed(d.unexplained)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      </Card>

      <Card title="What kind of difference it is">
        <div className="mechs">
          {causal.mechanisms.map((m) => {
            const top = [...m.byQuestion].sort((a, b) => Math.abs(b.severity) - Math.abs(a.severity)).slice(0, 3);
            return (
              <div className="mech" key={m.ta}>
                <div className="who"><b>{name(m.ta)}</b><Badge tone={KIND[m.kind].tone}>{KIND[m.kind].text}</Badge></div>
                <p>{named(m.explanation)}</p>
                {m.byQuestion.length > 1 && (
                  <p className="qeff">
                    Largest on {top.map((q) => `${q.question} (${signed(q.severity)})`).join(', ')}
                    {m.byQuestion.length > top.length && ` and ${m.byQuestion.length - top.length} more`}.
                  </p>
                )}
              </div>
            );
          })}
        </div>
      </Card>

      {new Set(questions).size > 1 && (
        <Card title="Where the TAs differed, question by question">
          <HowToRead>
            Each row is a question, the ones where the TAs differed most first; each column is a TA. A number is how many
            points harsher (red, +) or more lenient (blue, −) that TA was than the others on answers saying the same thing. A
            TA whose colour sits in a few rows read those questions' rubric differently; one coloured all the way down was
            harsher or more lenient throughout.
          </HowToRead>
          <QuestionHeatmap tas={causal.mechanisms.filter((m) => m.byQuestion.length > 0).map((m) => ({
            label: name(m.ta), cells: Object.fromEntries(m.byQuestion.map((q) => [q.question, q.severity])),
          }))} />
        </Card>
      )}

      <Card title="How sure can we be that the marking caused it?">
        <HowToRead>
          Comparing answers that say the same thing only shows the TA's effect if the comparison is fair. Each check tests one
          way it could be unfair. A green dot means the check passed; an amber dot means read that TA's figures with more
          caution, not that they are wrong.
        </HowToRead>
        <div className="checks">
          {checks.map((k) => (
            <div className="check" key={k.name}>
              <span className={`dot ${k.ok ? 'ok' : 'warn'}`} aria-label={k.ok ? 'passed' : 'look closer'} />
              <div>
                <div className="name">{k.name}</div>
                <div className="q">{k.q}</div>
                <div className="res">{k.res}</div>
              </div>
            </div>
          ))}
        </div>
        {causal.sensitivity.length > 0 && (
          <>
            <h4>Sensitivity to what the embedding might miss</h4>
            <p className="muted small">
              Γ is how strongly a hidden quality difference would have to favour one side of every matched pair to overturn the
              conclusion. Γ = 1: not conclusive even without hidden bias; 2 or more: robust (Rosenbaum bounds).
            </p>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>TA</th><th>Matched pairs</th><th>Direction</th><th>p, no hidden bias</th><th>Robust up to Γ</th></tr></thead>
                <tbody>
                  {causal.sensitivity.map((s) => (
                    <tr key={s.ta}>
                      <td>{name(s.ta)}</td><td className="n">{s.pairs}</td><td>{s.direction > 0 ? 'harsher' : 'more lenient'}</td>
                      <td className="n">{pValue(s.p)}</td><td className="n">{s.gamma <= 1 ? 'not conclusive' : s.gamma.toFixed(1)}</td>
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
