import type { Causal, MechanismKind, Result } from '../api';
import { Badge, Card, Empty, HowToRead } from '../components/ui';
import { DecompositionChart, Heatmap, VarianceBar } from '../components/charts';
import { useName, useNamedText } from '../names';
import { pct, pValue, signed } from '../format';

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

function CausalView({ causal, r }: { causal: Causal; r: Result }) {
  const name = useName();
  const named = useNamedText();
  const c = r.content!;
  const real = r.noEffect?.p != null && r.noEffect.p < 0.05;
  const minShare = Math.min(...causal.overlap.map((o) => o.matchedShare));
  const worstAfter = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.after ?? 0)));
  const worstBefore = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.before)));
  const worstLength = Math.max(0, ...causal.overlap.map((o) => Math.abs(o.length ?? 0)));
  const questions = Array.from(new Set(causal.mechanisms.flatMap((m) => m.byQuestion.map((q) => q.question))));

  return (
    <div className="panel">
      <Card title="Why did the marks differ?">
        <p style={{ marginTop: 0 }}>
          A difference between two TAs' piles has two possible causes: the students in one pile wrote better answers (a
          legitimate difference), or the TA marked the same work differently (a marking effect). Even Hand separates them by
          comparing <b>answers that say the same thing</b>: for those, what was written cannot explain a difference in marks,
          so whatever remains was caused by who marked them.
        </p>
        <p className="muted small">
          Answers read by {c.embedder}. {pct(c.textCoverage)} of responses carry text; what an answer says explains{' '}
          {pct(c.signal)} of how its mark varies within a TA's pile; {pct(c.matchedShare)} of answers have a close match marked
          by another TA.
        </p>
        {c.notes.map((n, i) => <p key={i} className="muted small">{n}</p>)}
      </Card>

      <Card title="What the variation in marks is made of">
        <VarianceBar content={causal.variance.content} ta={causal.variance.ta} residual={causal.variance.residual} />
      </Card>

      <Card title="Each TA's difference, split into its causes">
        <p className="muted small" style={{ marginTop: 0 }}>
          Points of each question's maximum, relative to the class on the same questions. The black line is how much higher
          (or lower) the pile's marks were; the bars say why.
        </p>
        <HowToRead>
          Take one TA's pile. The black line says how much higher (right) or lower (left) its marks were than the class's on the
          same questions. The green part is how much better or worse that pile's answers were: a legitimate reason. The red part
          is the TA's own marking: the part worth correcting. The grey part is what neither explains, such as chance.
        </HowToRead>
        <DecompositionChart rows={causal.decomposition.map((d) => ({
          label: name(d.ta), raw: d.raw, content: d.content, marking: d.marking, unexplained: d.unexplained,
        }))} />
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>TA</th><th>Answers</th><th>Pile vs class</th><th>= what students wrote</th><th>+ how it was marked</th><th>+ neither</th></tr></thead>
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
      </Card>

      <Card title="What kind of difference it is">
        <div className="mechs">
          {causal.mechanisms.map((m) => (
            <div className="mech" key={m.ta}>
              <div className="row" style={{ gap: 8 }}><Badge tone={KIND[m.kind].tone}>{KIND[m.kind].text}</Badge> <b>{name(m.ta)}</b></div>
              <p>{named(m.explanation)}</p>
              {m.byQuestion.length > 1 && (
                <div className="qeff">
                  <span>Largest on:</span>
                  {[...m.byQuestion].sort((a, b) => Math.abs(b.severity) - Math.abs(a.severity)).slice(0, 5)
                    .map((q) => <span key={q.question}>{q.question} <b>{signed(q.severity)}</b></span>)}
                  {m.byQuestion.length > 5 && <span>and {m.byQuestion.length - 5} more questions</span>}
                </div>
              )}
            </div>
          ))}
        </div>
        {questions.length > 1 && questions.length <= 40 && (
          <>
            <h4>Each TA on each question</h4>
            <Heatmap questions={questions} rows={causal.mechanisms.map((m) => ({
              label: name(m.ta), cells: Object.fromEntries(m.byQuestion.map((q) => [q.question, q.severity])),
            }))} />
          </>
        )}
      </Card>

      <Card title="How sure can we be that the marking caused it?">
        <HowToRead>
          Comparing answers that say the same thing only shows the TA's effect if the comparison is fair. Each check below tests
          one way it could be unfair. A green dot means the check passed; an amber dot means read that TA's figures with more
          caution, not that they are wrong.
        </HowToRead>
        <div className="checks">
          <Check ok={minShare >= 0.5} name="Comparable answers exist"
                 q="Does every TA have answers that closely match answers marked by others?"
                 res={`at least ${pct(minShare)} of each TA's answers have a close match`} />
          <Check ok={worstAfter < 0.25} name="Like is compared with like"
                 q="After matching, are paired answers about equally good by the content model? (under 0.25; the content correction removes the rest)"
                 res={`${worstAfter.toFixed(2)} after matching (${worstBefore.toFixed(2)} between whole piles)`} />
          <Check ok={worstLength < 0.25} name="Negative control: answer length"
                 q="A TA cannot change how much a student wrote, and nothing adjusts for it: matched answers should not differ in length"
                 res={worstLength.toFixed(2)} />
          {causal.doubleMarked && (
            <Check ok={causal.doubleMarked.agreement == null || causal.doubleMarked.agreement >= 0.7} name="Same script, two TAs"
                   q="Scripts marked twice compare TAs on identical content: do they agree with the matched-answer estimate?"
                   res={`${causal.doubleMarked.scripts} scripts; correlation ${causal.doubleMarked.agreement == null ? 'n/a' : causal.doubleMarked.agreement.toFixed(2)}`} />
          )}
          <Check ok={!real || causal.placebo.p < 0.05} name="Placebo"
                 q="With TA labels shuffled among answers of similar quality, does the method still find differences this large?"
                 res={`p = ${pValue(causal.placebo.p)} (spread ${causal.placebo.observed.toFixed(1)} observed, ${causal.placebo.shuffled.toFixed(1)} shuffled)`} />
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
                <thead><tr><th>TA</th><th>Matched pairs</th><th>Direction</th><th>p (no hidden bias)</th><th>Robust up to Γ</th></tr></thead>
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

function Check({ ok, name, q, res }: { ok: boolean; name: string; q: string; res: string }) {
  return (
    <div className="check">
      <span className={`dot ${ok ? 'ok' : 'warn'}`} aria-label={ok ? 'passed' : 'look closer'} />
      <div><div className="name">{name}</div><div className="q">{q}</div></div>
      <div className="res">{res}</div>
    </div>
  );
}
