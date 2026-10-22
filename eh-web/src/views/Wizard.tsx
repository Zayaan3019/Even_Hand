import { useEffect, useMemo, useState, type DragEvent } from 'react';
import type { AnalysisSettings, Inspection, Status } from '../api';

const MAX_BYTES = 50 * 1024 * 1024;

/** Checks a chosen file before it is sent: a sentence the person can act on, or null. */
export function fileProblem(file: File): string | null {
  if (/\.(xls|ods|numbers)$/i.test(file.name)) {
    return 'That spreadsheet format cannot be read directly. Open it in Excel and save it as an Excel Workbook (.xlsx) or as CSV UTF-8.';
  }
  if (!/\.(csv|xlsx)$/i.test(file.name) && file.type !== 'text/csv') {
    return 'Choose an Excel workbook (.xlsx) or a CSV file.';
  }
  if (file.size > MAX_BYTES) return 'That file is larger than 50 MB. Split it by assessment and check each part.';
  if (file.size === 0) return 'That file is empty.';
  return null;
}

export function UploadStep({ onFile, problem, busy, done }: {
  onFile: (f: File) => void;
  problem: string | null;
  busy: boolean;
  done: boolean;
}) {
  const [over, setOver] = useState(false);
  const drop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    const f = e.dataTransfer.files[0];
    if (f) onFile(f);
  };
  return (
    <section className={`card ${done ? 'done' : ''}`}>
      <h3><span className="num">1</span>Your marks file</h3>
      <p className="lede">The sheet you already keep, or your exam platform's export, with a column saying which TA marked each script.</p>
      <label className={`drop ${over ? 'over' : ''}`} onDragOver={(e) => { e.preventDefault(); setOver(true); }}
             onDragLeave={() => setOver(false)} onDrop={drop}>
        <input type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
               aria-label="Choose your marks file" disabled={busy}
               onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }} />
        <strong>{busy ? 'Reading the file…' : 'Drop your marks file here, or click to choose it'}</strong>
        <span>An Excel workbook (.xlsx, first sheet) or a .csv file, as your exam platform exports it.</span>
      </label>
      <div className="row" style={{ marginTop: 12 }}>
        <span className="muted small"><a href="/template.csv" download>Download a blank template</a> (headers only)</span>
      </div>
      {problem && <div className="problem" role="alert"><strong>This file could not be read.</strong>{problem}</div>}
      <FormatHelp />
    </section>
  );
}

function FormatHelp() {
  return (
    <details className="help">
      <summary>What should the file look like?</summary>
      <div className="formats">
        <div>
          <h4>One row per student (most mark sheets)</h4>
          <div className="sample"><table><tbody>
            <tr><th>roll_no</th><th>ta</th><th>Q1 (10)</th><th>Q1 answer</th><th>Q2 (15)</th></tr>
            <tr><td>KEY</td><td></td><td></td><td>Your model answer</td><td></td></tr>
            <tr><td>CE26B001</td><td>Ravi</td><td>7</td><td>The moment is…</td><td>12</td></tr>
          </tbody></table></div>
          <p>Write each question's maximum in brackets in its header. Answer columns are optional and named like their
            question: <b>Q1 answer</b>, <b>Answer Q1</b>, <b>Q1 (response)</b>. A row whose roll number is <b>KEY</b> holds
            your model answers. Name and Total columns are ignored; a blank cell or AB means not answered.</p>
        </div>
        <div>
          <h4>One row per answer (when TAs split by question)</h4>
          <div className="sample"><table><tbody>
            <tr><th>roll_no</th><th>assessment</th><th>question</th><th>max_marks</th><th>marks</th><th>ta</th><th>answer</th></tr>
            <tr><td>CE26B001</td><td>Quiz 1</td><td>Q1</td><td>10</td><td>7</td><td>Ravi</td><td>The moment…</td></tr>
          </tbody></table></div>
          <p>Column names are matched loosely: Roll No, roll-no and roll_no all work, and so do TA, marker, evaluator or
            Graded by. A script marked twice is welcome: it links two TAs directly.</p>
        </div>
        <div>
          <h4>Exam platforms, as exported</h4>
          <p><b>Moodle and other LMS quizzes</b>: <code>Q. 1 /10.00</code> columns, <code>Response 1</code> for the answers, and a
            <b> Graded by</b> column.</p>
          <p><b>Google Forms quizzes</b>: the responses sheet with its <code>Score</code> (31 / 40) and answer columns, plus a
            <b> TA</b> column.</p>
        </div>
      </div>
    </details>
  );
}

export const RULES = [
  { value: 'relative', label: 'Relative grading: B at the class average, one grade per standard deviation, top 10% get S' },
  { value: 'relative-sigma', label: 'Relative grading, without the fixed top-10% S' },
  { value: 'absolute:S=90,A=80,B=70,C=60,D=50,E=40', label: 'Absolute cut-offs: S 90, A 80, B 70, C 60, D 50, E 40' },
];

export function ReviewStep({ fileName, info, status, busy, done, onRun }: {
  fileName: string;
  info: Inspection;
  status: Status | null;
  busy: boolean;
  done: boolean;
  onRun: (s: AnalysisSettings) => void;
}) {
  const assessments = useMemo(() => info.assessments.filter((a) => a !== ''), [info]);
  const [name, setName] = useState(fileName.replace(/\.(csv|xlsx)$/i, ''));
  const [rule, setRule] = useState(RULES[0].value);
  const [weights, setWeights] = useState<Record<string, string>>({});
  useEffect(() => {
    const equal = assessments.length ? Math.round(10000 / assessments.length) / 100 : 100;
    setWeights(Object.fromEntries(assessments.map((a) => [a, String(equal)])));
  }, [assessments]);
  const values = Object.values(weights).map(Number);
  const badWeight = values.some((v) => !(v > 0));
  const total = values.reduce((a, b) => a + (b || 0), 0);
  const canRun = info.rows > 0 && !badWeight && !busy;
  const model = status?.semantic ? status.embedder.split(' (')[0] : null;

  return (
    <section className={`card ${done ? 'done' : ''}`}>
      <h3><span className="num">2</span>Check what was read</h3>
      <p className="lede">Read from <b>{fileName}</b>. If these numbers look wrong, the file is not what you meant to choose.</p>
      <div className="tiles">
        <Count v={info.students} k="students" />
        <Count v={info.assistants} k="TAs" />
        <Count v={info.questions} k="questions" />
        <Count v={Math.max(1, assessments.length)} k={assessments.length > 1 ? 'assessments' : 'assessment'} />
        <Count v={info.rows} k="marks read" />
        <Count v={info.answers} k="written answers" hl={info.answers > 0} />
        {info.keys > 0 && <Count v={info.keys} k="answer-key questions" hl />}
      </div>
      {info.answers > 0 ? (
        <p className="notice good">
          ✓ <b>{info.answers.toLocaleString()} written answers</b> will be read
          {model ? <> by {model}, on this computer,</> : <> by their wording (no language model is installed),</>} to check
          whether what students wrote explains the differences between TAs.
          {info.keys > 0 ? ' Your answer key is used as a reference for what a good answer says.'
            : <> Tip: a row with roll number <b>KEY</b> holding your model answers sharpens the comparison.</>}
        </p>
      ) : (
        <p className="notice warn">
          No written answers in this file: the report will say how differently the TAs marked, but not whether the answers
          explain it. Add an answer column (such as <b>Q1 answer</b>) to get that.
        </p>
      )}
      {info.findings.length > 0 && (
        <ul className="findings">
          {info.findings.map((f, i) => (
            <li key={i} className={f.severity}>
              <span className="tag">{f.severity === 'error' ? 'Problem' : f.severity === 'warning' ? 'Check' : 'Note'}</span>
              {f.message}{f.more > 0 && <i> (and {f.more} more like it)</i>}
              {f.fix && <span className="fix">How to fix: {f.fix}</span>}
            </li>
          ))}
        </ul>
      )}

      <label className="field" htmlFor="course">Course name <small>appears on the report</small></label>
      <input id="course" className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="CE3020 Jul–Nov 2026" />

      {assessments.length > 1 && (
        <>
          <span className="field">How much each assessment counts <small>in % of the final total; leave equal if unsure</small></span>
          <div className="weights">
            {assessments.map((a) => (
              <label key={a}>{a}
                <span className="w">
                  <input className="input" type="number" min={0} step="any" value={weights[a] ?? ''}
                         aria-label={`Weight of ${a}`} onChange={(e) => setWeights({ ...weights, [a]: e.target.value })} /> %
                </span>
              </label>
            ))}
          </div>
          <div className={`sum ${badWeight || Math.abs(total - 100) > 0.5 ? 'off' : ''}`}>
            {badWeight ? 'Every assessment needs a weight above zero.'
              : `Total: ${Math.round(total * 10) / 10}%${Math.abs(total - 100) > 0.5 ? ' (does not need to be 100; only the proportions matter)' : ''}`}
          </div>
        </>
      )}

      <label className="field" htmlFor="rule">How grades are decided</label>
      <select id="rule" className="input" value={rule} onChange={(e) => setRule(e.target.value)}>
        {RULES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
      </select>

      <div className="row" style={{ marginTop: 18 }}>
        <button className="btn primary" disabled={!canRun}
                onClick={() => onRun({ name, rule, weights: assessments.length > 1
                  ? Object.fromEntries(Object.entries(weights).map(([k, v]) => [k, Number(v)])) : {} })}>
          {busy ? <><span className="spinner" /> Checking…</> : 'Check the marking →'}
        </button>
        {info.rows === 0 && <span className="muted">No usable marks were found; see the problems above.</span>}
      </div>
    </section>
  );
}

function Count({ v, k, hl }: { v: number; k: string; hl?: boolean }) {
  return (
    <div className={`tile ${hl ? 'hl' : ''}`}>
      <div className="v">{v.toLocaleString()}</div>
      <div className="k">{k}</div>
    </div>
  );
}

/** The pipeline's stages (eh-app Pipeline.run), each recognised by a word in the stage it reports. */
const STAGES = [
  { label: 'Reading the file', key: 'Reading the file' },
  { label: 'Who marked what', key: 'Checking who' },
  { label: 'Severity from the marks', key: 'Estimating' },
  { label: 'Reading the answers', key: 'answer' },
  { label: 'Comparing answers, causal checks', key: 'Comparing' },
  { label: 'Regrading', key: 'Regrading' },
  { label: 'Writing the report', key: 'Writing' },
];

/** Index of the stage under way: the last one whose key appears in the reported stage. */
export function stageIndex(stage: string): number {
  if (stage === 'Done') return STAGES.length;
  let at = 0;
  STAGES.forEach((s, i) => { if (stage.includes(s.key)) at = i; });
  return at;
}

export function ProgressStep({ stage, fraction, started }: { stage: string; fraction: number; started: number }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(t);
  }, []);
  const current = stageIndex(stage);
  return (
    <div className="progress" aria-live="polite">
      <div className="track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(100 * fraction)}>
        <div className="fill" style={{ width: `${Math.round(100 * fraction)}%` }} />
      </div>
      <div className="pstage">
        <span><span className="spinner" /> {stage}</span>
        <span>{Math.round((now - started) / 1000)} s</span>
      </div>
      <div className="stages">
        {STAGES.map((s, i) => (
          <span key={s.label} className={i < current ? 'past' : i === current ? 'on' : ''}>
            {i < current ? '✓ ' : ''}{s.label}
          </span>
        ))}
      </div>
    </div>
  );
}
