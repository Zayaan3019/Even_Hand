import { useState } from 'react';
import { useTooltip } from './ui';
import { inMarks, naturalCompare, signed } from '../format';

/** One TA's severity with its 95% interval; an optional second estimate is drawn as a hollow ring. */
export interface SeverityPoint {
  label: string;
  value: number;
  lo: number;
  hi: number;
  other?: number;
}

export function SeverityChart({ points, otherName }: { points: SeverityPoint[]; otherName?: string }) {
  const tip = useTooltip();
  let range = 4;
  for (const p of points) range = Math.max(range, Math.abs(p.lo), Math.abs(p.hi), Math.abs(p.other ?? 0));
  range = Math.ceil(range / 2) * 2;
  const rowH = 34, left = 150, width = 520, top = 10;
  const h = top + rowH * points.length + 30;
  const x = (v: number) => left + width / 2 + (v * width) / 2 / range;
  const ticks: number[] = [];
  const step = Math.max(2, Math.round(range / 2 / 2) * 2);
  for (let t = -range; t <= range + 1e-9; t += step) ticks.push(t);
  return (
    <div>
      <svg className="chart" viewBox={`0 0 ${left + width + 20} ${h}`} role="img"
           aria-label="Severity of each TA, with 95% intervals">
        <rect className="zone-l" x={left} y={top - 4} width={width / 2} height={h - top - 22} />
        <rect className="zone-h" x={left + width / 2} y={top - 4} width={width / 2} height={h - top - 22} />
        {ticks.map((t) => (
          <g key={t}>
            <line className={t === 0 ? 'axis' : 'grid'} x1={x(t)} x2={x(t)} y1={top - 4} y2={h - 26} />
            <text x={x(t)} y={h - 8} textAnchor="middle">{signed(t, 0)}</text>
          </g>
        ))}
        {points.map((p, i) => {
          const y = top + 12 + i * rowH;
          const text = `${p.label}: ${signed(p.value)} points (95% interval ${signed(p.lo)} to ${signed(p.hi)}), `
            + `${inMarks(p.value)} on a 10-mark question`;
          return (
            <g key={p.label} onMouseMove={(e) => tip.show(e, text)} onMouseLeave={tip.hide}>
              <rect x={0} y={y - rowH / 2} width={left + width} height={rowH} fill="transparent" />
              <text x={left - 12} y={y + 4} textAnchor="end" style={{ fill: 'var(--fg)' }}>{p.label}</text>
              <line className="ci" x1={x(p.lo)} x2={x(p.hi)} y1={y} y2={y} />
              {p.other != null && <circle className="other" cx={x(p.other)} cy={y} r={7} />}
              <circle className={p.value >= 0 ? 'harsh' : 'lenient'} cx={x(p.value)} cy={y} r={7} />
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span>← more lenient · harsher →</span>
        <span><span className="key" style={{ background: 'var(--harsh)' }} />harsher than average</span>
        <span><span className="key" style={{ background: 'var(--lenient)' }} />more lenient</span>
        {otherName && <span>○ {otherName}</span>}
        <span>bars: 95% intervals · points of a question's maximum</span>
      </div>
      {tip.node}
    </div>
  );
}

export function VarianceBar({ content, ta, residual }: { content: number; ta: number; residual: number }) {
  return (
    <div>
      <div className="vbar" role="img"
           aria-label={`What students wrote ${Math.round(content)}%, who marked ${Math.round(ta)}%, neither ${Math.round(residual)}%`}>
        <span className="c-content" style={{ width: `${content}%` }} />
        <span className="c-ta" style={{ width: `${ta}%` }} />
        <span className="c-res" style={{ width: `${residual}%` }} />
      </div>
      <div className="legend">
        <span><span className="key c-content" />What students wrote <b>{Math.round(content)}%</b></span>
        <span><span className="key c-ta" />Who marked it <b>{Math.round(ta)}%</b></span>
        <span><span className="key c-res" />Neither (chance, rubric ambiguity) <b>{Math.round(residual)}%</b></span>
      </div>
    </div>
  );
}

/** Each TA's raw gap as the sum of what students wrote, how it was marked, and neither. */
export function DecompositionChart({ rows }: {
  rows: { label: string; raw: number; content: number; marking: number; unexplained: number }[];
}) {
  const tip = useTooltip();
  let range = 4;
  for (const r of rows) {
    const pos = Math.max(0, r.content) + Math.max(0, r.marking) + Math.max(0, r.unexplained);
    const neg = Math.min(0, r.content) + Math.min(0, r.marking) + Math.min(0, r.unexplained);
    range = Math.max(range, pos, -neg, Math.abs(r.raw));
  }
  range = Math.ceil(range / 2) * 2;
  const rowH = 34, left = 150, width = 520, top = 10;
  const h = top + rowH * rows.length + 30;
  const x = (v: number) => left + width / 2 + (v * width) / 2 / range;
  const parts = [
    { key: 'content' as const, cls: 'var(--content)', name: 'what students wrote' },
    { key: 'marking' as const, cls: 'var(--ta)', name: 'how it was marked' },
    { key: 'unexplained' as const, cls: 'var(--residual)', name: 'neither' },
  ];
  return (
    <div>
      <svg className="chart" viewBox={`0 0 ${left + width + 20} ${h}`} role="img"
           aria-label="Each TA's difference from the class, split into its causes">
        <line className="axis" x1={x(0)} x2={x(0)} y1={top - 4} y2={h - 26} />
        {[-range, -range / 2, range / 2, range].map((t) => (
          <text key={t} x={x(t)} y={h - 8} textAnchor="middle">{signed(t, 0)}</text>
        ))}
        {rows.map((r, i) => {
          const y = top + 12 + i * rowH;
          let pos = 0, neg = 0;
          return (
            <g key={r.label}>
              <text x={left - 12} y={y + 4} textAnchor="end" style={{ fill: 'var(--fg)' }}>{r.label}</text>
              {parts.map((p) => {
                const v = r[p.key];
                const start = v >= 0 ? pos : neg + v;
                if (v >= 0) pos += v; else neg += v;
                const text = `${r.label}: ${signed(v)} points from ${p.name}`;
                return (
                  <rect key={p.key} x={x(start)} y={y - 9} width={Math.max(0.5, x(start + Math.abs(v)) - x(start))}
                        height={18} rx={3} fill={p.cls} onMouseMove={(e) => tip.show(e, text)} onMouseLeave={tip.hide} />
                );
              })}
              <line x1={x(r.raw)} x2={x(r.raw)} y1={y - 13} y2={y + 13} stroke="var(--fg)" strokeWidth={2.5}
                    onMouseMove={(e) => tip.show(e, `${r.label}: pile's marks ${signed(r.raw)} points vs the class`)}
                    onMouseLeave={tip.hide} />
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><span className="key c-content" />what students wrote</span>
        <span><span className="key c-ta" />how it was marked</span>
        <span><span className="key c-res" />neither</span>
        <span>│ the pile's marks vs the class</span>
      </div>
      {tip.node}
    </div>
  );
}

export function GradeChart({ grades }: { grades: { grade: string; asMarked: number; corrected: number }[] }) {
  const tip = useTooltip();
  const max = Math.max(1, ...grades.flatMap((g) => [g.asMarked, g.corrected]));
  const w = 640, h = 220, left = 30, bottom = 26, top = 10;
  const slot = (w - left) / grades.length;
  const y = (v: number) => top + (h - top - bottom) * (1 - v / max);
  return (
    <div>
      <svg className="chart" viewBox={`0 0 ${w} ${h}`} role="img" aria-label="Letter grades as marked and corrected">
        {[0, 0.5, 1].map((f) => (
          <g key={f}>
            <line className="grid" x1={left} x2={w} y1={y(max * f)} y2={y(max * f)} />
            <text x={left - 6} y={y(max * f) + 4} textAnchor="end">{Math.round(max * f)}</text>
          </g>
        ))}
        {grades.map((g, i) => {
          const cx = left + slot * i + slot / 2;
          const bw = Math.min(22, slot / 3);
          return (
            <g key={g.grade}>
              <rect x={cx - bw - 2} y={y(g.asMarked)} width={bw} height={h - bottom - y(g.asMarked)} rx={3}
                    fill="var(--muted)" onMouseMove={(e) => tip.show(e, `${g.grade} as marked: ${g.asMarked}`)}
                    onMouseLeave={tip.hide} />
              <rect x={cx + 2} y={y(g.corrected)} width={bw} height={h - bottom - y(g.corrected)} rx={3}
                    fill="var(--accent)" onMouseMove={(e) => tip.show(e, `${g.grade} corrected: ${g.corrected}`)}
                    onMouseLeave={tip.hide} />
              <text x={cx} y={h - 8} textAnchor="middle" style={{ fill: 'var(--fg)', fontWeight: 600 }}>{g.grade}</text>
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><span className="key" style={{ background: 'var(--muted)' }} />as marked</span>
        <span><span className="key" style={{ background: 'var(--accent)' }} />as if every TA marked like the average</span>
      </div>
      {tip.node}
    </div>
  );
}

/** Questions shown before "Show all": enough to see the pattern, short enough to read without scrolling. */
const TOP_QUESTIONS = 10;

/**
 * Question by TA: each TA's effect on each question, coloured by direction and size. Questions are rows and TAs columns,
 * so the table grows downwards with the number of questions and always fits the width of the page.
 */
export function QuestionHeatmap({ tas }: { tas: { label: string; cells: Record<string, number> }[] }) {
  const tip = useTooltip();
  const [all, setAll] = useState(false);
  const rows = Array.from(new Set(tas.flatMap((t) => Object.keys(t.cells))))
    .map((q) => ({ q, spread: Math.max(...tas.map((t) => Math.abs(t.cells[q] ?? 0))) }))
    .sort((a, b) => b.spread - a.spread || naturalCompare(a.q, b.q));
  const shown = all ? rows : rows.slice(0, TOP_QUESTIONS);
  const max = Math.max(5, ...rows.map((r) => r.spread));
  // Beyond a dozen TAs the numbers no longer fit side by side; colour carries the reading and the tooltip the figure.
  const numbers = tas.length <= 12;
  const colour = (v: number) => {
    const a = Math.min(1, Math.abs(v) / max);
    const base = v >= 0 ? 'var(--harsh)' : 'var(--lenient)';
    return `color-mix(in srgb, ${base} ${Math.round(12 + 70 * a)}%, transparent)`;
  };
  return (
    <div>
      {/* About 130px per TA is enough for a figure; wider cells only spread the eye. */}
      <table className="heat" aria-label="Each TA's effect on each question, in points"
             style={{ maxWidth: 220 + 130 * tas.length }}>
        <colgroup><col className="qcol" />{tas.map((t) => <col key={t.label} />)}</colgroup>
        <thead>
          <tr>
            <th scope="col">Question</th>
            {tas.map((t) => <th key={t.label} scope="col" title={t.label}>{t.label}</th>)}
          </tr>
        </thead>
        <tbody>
          {shown.map(({ q }) => (
            <tr key={q}>
              <th scope="row" title={q}>{q}</th>
              {tas.map((t) => {
                const v = t.cells[q];
                if (v == null) return <td key={t.label} className="none" aria-label="no matched answers">·</td>;
                const text = `${t.label} on ${q}: ${signed(v)} points (${inMarks(v)} on a 10-mark question)`;
                return (
                  <td key={t.label} style={{ background: colour(v) }} aria-label={text}
                      onMouseMove={(e) => tip.show(e, text)} onMouseLeave={tip.hide}>
                    {numbers ? signed(v, 0) : ''}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="legend">
        <span><span className="key" style={{ background: 'var(--harsh)' }} />harsher than the others</span>
        <span><span className="key" style={{ background: 'var(--lenient)' }} />more lenient</span>
        <span>· no matched answers</span>
        <span>points of a question's maximum</span>
      </div>
      {rows.length > TOP_QUESTIONS && (
        <button type="button" className="btn small-btn" onClick={() => setAll(!all)}>
          {all ? `Show only the ${TOP_QUESTIONS} largest` : `Show all ${rows.length} questions`}
        </button>
      )}
      {tip.node}
    </div>
  );
}
