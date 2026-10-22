// The contract with the Java server (eh-app LocalServer and ResultJson). Every figure is computed there;
// this app only presents it. All requests go to the same origin, 127.0.0.1, and nowhere else.

export interface Status {
  embedder: string;
  semantic: boolean;
  note: string;
  version: string;
}

export interface Finding {
  severity: 'error' | 'warning' | 'note';
  message: string;
  fix: string;
  more: number;
}

export interface Inspection {
  rows: number;
  students: number;
  assistants: number;
  questions: number;
  answers: number;
  keys: number;
  assessments: string[];
  findings: Finding[];
}

export interface TaEstimate {
  label: string;
  marks: number;
  severity: number;
  lo: number;
  hi: number;
  se: number;
  group: number;
  clear: boolean;
}

export interface Side {
  student: string;
  ta: string;
  marks: number;
  excerpt: string;
}

export interface PairEvidence {
  question: string;
  max: number;
  similarity: number;
  gap: number;
  first: Side;
  second: Side;
}

export interface OutOfLine {
  question: string;
  max: number;
  student: string;
  ta: string;
  marks: number;
  expected: number;
  z: number;
  excerpt: string;
}

export type MechanismKind =
  | 'CONSISTENT_SEVERITY'
  | 'QUESTION_SPECIFIC'
  | 'DRIFT'
  | 'ISOLATED_SLIPS'
  | 'NO_CLEAR_DIFFERENCE';

export interface Causal {
  variance: { content: number; ta: number; residual: number };
  decomposition: { ta: string; answers: number; raw: number; content: number; marking: number; unexplained: number }[];
  overlap: { ta: string; matchedShare: number; before: number; after: number | null; length: number | null }[];
  doubleMarked?: { scripts: number; agreement: number | null };
  placebo: { p: number; shuffles: number; observed: number; shuffled: number };
  sensitivity: { ta: string; pairs: number; direction: number; p: number; gamma: number }[];
  mechanisms: {
    ta: string;
    kind: MechanismKind;
    heterogeneityP: number | null;
    slips: number;
    explanation: string;
    byQuestion: { question: string; severity: number; se: number; pairs: number }[];
  }[];
}

export interface Content {
  status: 'USABLE' | 'WEAK_SIGNAL' | 'NO_LINKS' | 'NO_TEXT';
  embedder: string;
  semantic: boolean;
  textCoverage: number;
  signal: number;
  matchedShare: number;
  p: number | null;
  notes: string[];
  questions: { label: string; answers: number; signal: number; pairs: number; tas: number }[];
  severity: { label: string; severity: number; lo: number; hi: number; pairs: number }[];
  sameAnswer: PairEvidence[];
  sameTa: PairEvidence[];
  outOfLine: OutOfLine[];
  causal?: Causal;
}

export interface RecheckRow {
  student: string;
  total: number;
  corrected: number;
  grade: string;
  likely: string;
  probability: number;
  points: number;
}

export interface OutcomeData {
  share: number;
  changed: number;
  low: number;
  high: number;
  grades: { grade: string; asMarked: number; corrected: number }[];
  refused?: string;
  recheck?: RecheckRow[];
  expectedFalseFlags?: number;
}

export interface Result {
  course: { name: string; students: number; tas: number; questions: number; answers: number; keys: number };
  verdict: {
    kind: 'estimable' | 'under-exchangeability' | 'partial' | 'not-estimable' | 'from-answers';
    headline: string;
    exposure: string;
    exposureStatus: string;
    fromMarking: string;
  };
  basis: 'marks' | 'answers' | 'none';
  choice: string;
  /** The marks needed the equal-piles assumption, and the answers confirmed it (Hausman test). */
  confirmedByAnswers: boolean;
  bottomLineHtml: string;
  tas?: TaEstimate[];
  noEffect?: { p: number | null; simulations: number; spread: number };
  crossCheck?: { otherName: string; rows: { label: string; used: number; other: number; lo: number; hi: number }[] };
  content?: Content;
  outcome?: OutcomeData;
  diagnostics: Finding[];
  footnotes: string[];
  manifest: Record<string, string>;
  instructorHtml: string;
  committeeHtml: string;
  names: Record<string, string>;
}

export interface Job {
  state: 'running' | 'done' | 'failed';
  stage: string;
  fraction: number;
  error?: string;
  result?: Result;
}

export interface AnalysisSettings {
  name: string;
  weights: Record<string, number>;
  rule: string;
}

/** A failure the person can act on: the server's own sentence, or "Even Hand is not running". */
export class ApiError extends Error {}

async function json<T>(response: Promise<Response>): Promise<T> {
  let r: Response;
  try {
    r = await response;
  } catch {
    throw new ApiError('Even Hand is not responding. Its window may have been closed: double-click even-hand.cmd again, then reload this page.');
  }
  let body: unknown;
  try {
    body = await r.json();
  } catch {
    throw new ApiError(`Even Hand answered with something unexpected (HTTP ${r.status}).`);
  }
  if (!r.ok) {
    const message = (body as { error?: string }).error;
    throw new ApiError(message ?? `Even Hand could not do that (HTTP ${r.status}).`);
  }
  return body as T;
}

export function status(): Promise<Status> {
  return json<Status>(fetch('/status'));
}

/** What the file holds, before anything is analysed. The file goes as it is: CSV text or an Excel workbook. */
export function inspect(file: Blob): Promise<Inspection> {
  return json<Inspection>(fetch('/inspect', { method: 'POST', body: file }));
}

export function startAnalysis(file: Blob, settings: AnalysisSettings): Promise<{ job: string }> {
  const weights = Object.entries(settings.weights).map(([k, v]) => `${k}=${v}`).join(',');
  const q = new URLSearchParams({ name: settings.name || 'Course', weights, rule: settings.rule });
  return json<{ job: string }>(fetch(`/analyse?${q}`, { method: 'POST', body: file }));
}

export function job(id: string): Promise<Job> {
  return json<Job>(fetch(`/job?id=${encodeURIComponent(id)}`));
}
