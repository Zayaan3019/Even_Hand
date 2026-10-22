import type { Result } from '../api';
import { Card, Tile, Badge, HowToRead } from '../components/ui';
import { SeverityChart, VarianceBar } from '../components/charts';
import { useName, useNamedText } from '../names';
import { pct } from '../format';

const VERDICT_BADGE: Record<Result['verdict']['kind'], { tone: 'green' | 'amber' | 'red' | 'blue'; text: string }> = {
  estimable: { tone: 'green', text: 'Checkable from the marking' },
  'from-answers': { tone: 'green', text: 'Checkable through the answers' },
  'under-exchangeability': { tone: 'amber', text: 'Checkable if piles were alike' },
  partial: { tone: 'amber', text: 'Partly checkable' },
  'not-estimable': { tone: 'red', text: 'Not checkable' },
};

export function Overview({ r, go }: { r: Result; go: (section: string) => void }) {
  const name = useName();
  const named = useNamedText();
  const badge = r.confirmedByAnswers ? { tone: 'green' as const, text: 'Checkable: answers confirm the piles were alike' }
    : VERDICT_BADGE[r.verdict.kind];
  const tas = r.tas ?? [];
  const spread = tas.length ? (Math.max(...tas.map((t) => t.severity)) - Math.min(...tas.map((t) => t.severity))) / 10 : null;
  const causal = r.content?.causal;
  const recheck = r.outcome?.recheck?.length ?? 0;

  return (
    <div className="panel">
      <Card className="bottomline">
        <div className="row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
          <strong>Bottom line</strong>
          <Badge tone={badge.tone}>{badge.text}</Badge>
        </div>
        {/* Server-escaped HTML (HtmlReport.bottomLineHtml); only names are substituted, escaped, on this screen. */}
        <p dangerouslySetInnerHTML={{ __html: named(r.bottomLineHtml) }} />
      </Card>

      <div className="tiles">
        {spread != null && <Tile value={spread.toFixed(1)} label="marks between the harshest and most lenient TA on a 10-mark question" tone="accent" />}
        {r.outcome && <Tile value={r.outcome.changed} label="letter grades that depend on who marked" />}
        {r.outcome && <Tile value={recheck} label="scripts recommended for a second look" />}
        {causal && <Tile value={`${Math.round(causal.variance.content)}%`} label="of the variation in marks explained by what students wrote" tone="hl" />}
        {r.content && <Tile value={pct(r.content.textCoverage)} label="of responses had written answers" />}
      </div>

      {tas.length > 0 && (
        <Card title="How differently did the TAs mark?" action={<button className="btn small" onClick={() => go('tas')}>Details →</button>}>
          <p className="muted small" style={{ marginTop: 0 }}>
            {r.basis === 'answers' ? 'Compared on answers that say the same thing, marked by different TAs.'
              : 'From the marks, with the students resampled for each interval.'} Positive is harsher than the average TA.
          </p>
          <HowToRead>
            Each dot is one TA. To the right of the dashed line, the TA gave less than the average TA would have for the same
            answers; to the left, more. The grey bar around a dot is the range the true figure is very likely in (95%): if it
            crosses the dashed line, that TA's difference may be chance. +5 means about half a mark less on a 10-mark question.
          </HowToRead>
          <SeverityChart points={tas.map((t) => ({ label: name(t.label), value: t.severity, lo: t.lo, hi: t.hi }))} />
        </Card>
      )}

      <div className="two">
        {causal && (
          <Card title="Why did the marks vary?" action={<button className="btn small" onClick={() => go('why')}>Causes →</button>}>
            <VarianceBar content={causal.variance.content} ta={causal.variance.ta} residual={causal.variance.residual} />
            <p className="muted small">Shares of the variation in marks within each question. Only the middle part is the
              marking itself; the rest is what students wrote, chance and rubric ambiguity.</p>
          </Card>
        )}
        <Card title="Can this marking be checked?">
          {r.confirmedByAnswers ? (
            <>
              <p className="verdict" style={{ marginTop: 0 }}>Yes. {r.choice}</p>
              <p className="muted small">From who marked what alone: {r.verdict.headline}</p>
            </>
          ) : (
            <>
              <p className="verdict" style={{ marginTop: 0 }}>{r.verdict.headline}</p>
              {r.choice && <p className="small"><b>Which estimate corrects the grades.</b> {r.choice}</p>}
            </>
          )}
          <p className="muted small">{r.verdict.exposure}</p>
        </Card>
      </div>
    </div>
  );
}
