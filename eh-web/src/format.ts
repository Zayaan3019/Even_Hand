// Small, pure helpers shared by the views. Tested in src/test/format.test.ts.

/** A pseudonym ("Student 12", "Assistant 3") shown as the real name when names are on and known. */
export function displayName(label: string, names: Record<string, string>, showNames: boolean): string {
  if (!showNames) return label;
  return names[label] ?? label;
}

/** Every pseudonym inside a piece of text or HTML replaced by its real name, escaped for HTML. */
export function withNames(html: string, names: Record<string, string>, showNames: boolean): string {
  if (!showNames) return html;
  return html.replace(/\b(Student|Assistant) \d+\b/g, (m) => (names[m] ? escapeHtml(names[m]) : m));
}

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** "+4.3", "-1.0", "0.0": severity in percentage points, always signed. */
export function signed(x: number | null | undefined, digits = 1): string {
  if (x == null || !Number.isFinite(x)) return '–';
  const v = Math.abs(x) < 0.5 * 10 ** -digits ? 0 : x;
  return (v > 0 ? '+' : v < 0 ? '−' : '') + Math.abs(v).toFixed(digits);
}

/** A share 0..1 as a whole percentage. */
export function pct(x: number | null | undefined): string {
  if (x == null || !Number.isFinite(x)) return '–';
  return `${Math.round(100 * x)}%`;
}

/** A p-value as people read it. */
export function pValue(p: number | null | undefined): string {
  if (p == null || !Number.isFinite(p)) return '–';
  if (p < 0.001) return '< 0.001';
  return p.toFixed(3);
}

/** Marks as written: 7, 7.5. */
export function marks(x: number): string {
  return Number.isInteger(x) ? String(x) : x.toFixed(1);
}

/** Severity points as marks on a 10-mark question, in words: "0.4 marks less". */
export function inMarks(points: number): string {
  const m = points / 10;
  if (Math.abs(m) < 0.05) return 'about the same';
  return `${Math.abs(m).toFixed(1)} mark${Math.abs(m) === 1 ? '' : 's'} ${m > 0 ? 'less' : 'more'}`;
}

/** Turns "Assistant 3" sort keys into numbers so 10 sorts after 9. */
export function naturalCompare(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

/** A file name safe on every system, from the course name. */
export function fileStem(name: string): string {
  const stem = name.trim().replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '');
  return stem || 'course';
}
