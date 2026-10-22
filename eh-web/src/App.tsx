import { useCallback, useEffect, useRef, useState } from 'react';
import * as api from './api';
import type { AnalysisSettings, Inspection, Result, Status } from './api';
import { NamesContext } from './names';
import { fileStem, withNames } from './format';
import { ProgressStep, ReviewStep, UploadStep, fileProblem } from './views/Wizard';
import { Overview } from './views/Overview';
import { Tas } from './views/Tas';
import { Why } from './views/Why';
import { Evidence } from './views/Evidence';
import { Grades } from './views/Grades';
import { Details } from './views/Details';

type Phase =
  | { kind: 'upload'; problem: string | null; busy: boolean }
  | { kind: 'review'; file: File; info: Inspection; running: boolean; stage: string; fraction: number; started: number; problem: string | null }
  | { kind: 'result'; file: File; result: Result };

const POLL_MS = 400;

export default function App() {
  const [phase, setPhase] = useState<Phase>({ kind: 'upload', problem: null, busy: false });
  const [status, setStatus] = useState<Status | null>(null);
  const [statusError, setStatusError] = useState(false);
  const [theme, setTheme] = useState<'light' | 'dark'>(() => preferredTheme());
  const cancelled = useRef(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('even-hand-theme', theme); } catch { /* private window: fine */ }
  }, [theme]);

  useEffect(() => {
    cancelled.current = false;
    api.status().then(setStatus).catch(() => setStatusError(true));
    return () => { cancelled.current = true; };
  }, []);

  const chooseFile = useCallback(async (file: File) => {
    const problem = fileProblem(file);
    if (problem) {
      setPhase({ kind: 'upload', problem, busy: false });
      return;
    }
    setPhase({ kind: 'upload', problem: null, busy: true });
    try {
      const info = await api.inspect(file);
      setPhase({ kind: 'review', file, info, running: false, stage: '', fraction: 0, started: 0, problem: null });
    } catch (e) {
      setPhase({ kind: 'upload', problem: message(e) + '\n\nTip: download the blank template to see a layout that works.', busy: false });
    }
  }, []);

  const run = useCallback(async (settings: AnalysisSettings) => {
    if (phase.kind !== 'review') return;
    const { file, info } = phase;
    const started = Date.now();
    setPhase({ ...phase, running: true, stage: 'Starting', fraction: 0, started, problem: null });
    try {
      const { job } = await api.startAnalysis(file, settings);
      for (;;) {
        if (cancelled.current) return;
        const j = await api.job(job);
        if (j.state === 'running') {
          setPhase({ kind: 'review', file, info, running: true, stage: j.stage, fraction: j.fraction, started, problem: null });
          await sleep(POLL_MS);
          continue;
        }
        if (j.state === 'failed' || !j.result) throw new api.ApiError(j.error ?? 'The analysis failed.');
        setPhase({ kind: 'result', file, result: j.result });
        window.scrollTo({ top: 0 });
        return;
      }
    } catch (e) {
      setPhase({ kind: 'review', file, info, running: false, stage: '', fraction: 0, started, problem: message(e) });
    }
  }, [phase]);

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <div className="mark" aria-hidden="true">EH</div>
          <div>
            <h1>Even Hand</h1>
            <div className="tag">Did it matter which TA marked the script?</div>
          </div>
        </div>
        <div className="chips">
          <span className="chip ok" title="Served by this computer on 127.0.0.1; nothing is sent anywhere">🔒 This computer only</span>
          <ModelChip status={status} error={statusError} />
          <button className="icon-button" aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
                  onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☀' : '☾'}</button>
        </div>
      </header>

      {phase.kind === 'result'
        ? <Dashboard result={phase.result} onNew={() => setPhase({ kind: 'upload', problem: null, busy: false })} />
        : (
          <>
            <section className="hero">
              <h2>Check whether the TAs marked alike, and why they did not.</h2>
              <p>
                Give Even Hand your marks file, with the students' written answers if you have them. It tells you how differently
                each TA marked, whether what the students wrote explains it, which grades it moved, and which scripts deserve a
                second look. It never changes a mark.
              </p>
            </section>
            <div className="steps">
              <UploadStep onFile={chooseFile} problem={phase.kind === 'upload' ? phase.problem : null}
                          busy={phase.kind === 'upload' && phase.busy} done={phase.kind === 'review'} />
              {phase.kind === 'review' && (
                <>
                  <ReviewStep key={phase.file.name + phase.file.size} fileName={phase.file.name} info={phase.info}
                              status={status} busy={phase.running} done={false} onRun={run} />
                  {phase.running && <ProgressStep stage={phase.stage} fraction={phase.fraction} started={phase.started} />}
                  {phase.problem && (
                    <div className="problem" role="alert"><strong>The marking could not be checked.</strong>{phase.problem}</div>
                  )}
                </>
              )}
            </div>
          </>
        )}
      <footer className="footer">
        Even Hand {status?.version ? `· ${status.version}` : ''} · CS5013, IIT Madras · works offline · nothing is stored on disk
      </footer>
    </div>
  );
}

function ModelChip({ status, error }: { status: Status | null; error: boolean }) {
  if (error) return <span className="chip warn">Not connected</span>;
  if (!status) return <span className="chip">Checking the answer reader…</span>;
  return status.semantic
    ? <span className="chip ok" title={status.note}>Answer reader: {status.embedder.split(' (')[0]}</span>
    : <span className="chip warn" title={status.note}>Answers compared by wording only</span>;
}

const SECTIONS = [
  { id: 'overview', label: 'Overview' },
  { id: 'tas', label: 'TAs' },
  { id: 'why', label: 'Why marks differed' },
  { id: 'evidence', label: 'Evidence' },
  { id: 'grades', label: 'Grades & re-checks' },
  { id: 'details', label: 'Details' },
] as const;

function Dashboard({ result, onNew }: { result: Result; onNew: () => void }) {
  const [section, setSection] = useState<string>('overview');
  const [showNames, setShowNames] = useState(true);
  const evidenceCount = (result.content?.sameAnswer.length ?? 0) + (result.content?.sameTa.length ?? 0)
    + (result.content?.outOfLine.length ?? 0);
  const counts: Record<string, number | undefined> = {
    evidence: result.content ? evidenceCount : undefined,
    grades: result.outcome?.recheck?.length,
  };
  const stem = fileStem(result.course.name);

  return (
    <NamesContext.Provider value={{ show: showNames, table: result.names }}>
      <div className="dash-head">
        <div>
          <h2>{result.course.name}</h2>
          <div className="meta">
            {result.course.students.toLocaleString()} students · {result.course.tas} TAs · {result.course.questions} questions
            {result.course.answers > 0 && <> · {result.course.answers.toLocaleString()} written answers</>}
          </div>
        </div>
        <div className="toolbar">
          <label className="switch"><input type="checkbox" checked={showNames} onChange={(e) => setShowNames(e.target.checked)} />
            Show roll numbers and TA names</label>
          <button className="btn small" title="With pseudonyms only: safe to file or forward"
                  onClick={() => download(result.instructorHtml, `${stem}-even-hand-report.html`)}>Save report</button>
          <button className="btn small" title="Aggregates only: no TA, no student and no answer appears"
                  onClick={() => download(result.committeeHtml, `${stem}-class-committee.html`)}>Save Class Committee sheet</button>
          <button className="btn small" onClick={() => printHtml(withNames(result.instructorHtml, result.names, showNames))}>Print</button>
          <button className="btn small" onClick={onNew}>Check another file</button>
        </div>
      </div>
      <div className="layout">
        <nav className="nav" aria-label="Sections">
          {SECTIONS.map((s) => (
            <button key={s.id} className={section === s.id ? 'active' : ''} aria-current={section === s.id ? 'page' : undefined}
                    onClick={() => { setSection(s.id); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>
              {s.label}{counts[s.id] != null && <span className="count">{counts[s.id]}</span>}
            </button>
          ))}
        </nav>
        <main>
          {section === 'overview' && <Overview r={result} go={setSection} />}
          {section === 'tas' && <Tas r={result} />}
          {section === 'why' && <Why r={result} />}
          {section === 'evidence' && <Evidence r={result} />}
          {section === 'grades' && <Grades r={result} />}
          {section === 'details' && <Details r={result} />}
        </main>
      </div>
    </NamesContext.Provider>
  );
}

function download(html: string, filename: string) {
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

function printHtml(html: string) {
  const frame = document.createElement('iframe');
  frame.style.position = 'fixed';
  frame.style.width = '0';
  frame.style.height = '0';
  frame.style.border = '0';
  frame.setAttribute('aria-hidden', 'true');
  frame.srcdoc = html;
  frame.onload = () => {
    frame.contentWindow?.focus();
    frame.contentWindow?.print();
    setTimeout(() => frame.remove(), 60_000);
  };
  document.body.appendChild(frame);
}

function preferredTheme(): 'light' | 'dark' {
  try {
    const saved = localStorage.getItem('even-hand-theme');
    if (saved === 'light' || saved === 'dark') return saved;
  } catch { /* storage unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
