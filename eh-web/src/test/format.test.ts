import { describe, expect, it } from 'vitest';
import { displayName, escapeHtml, fileStem, inMarks, marks, naturalCompare, pValue, pct, signed, withNames } from '../format';
import { fileProblem, stageIndex } from '../views/Wizard';

describe('names stay on screen only when switched on', () => {
  const table = { 'Student 1': 'CE23B092', 'Assistant 2': 'Ravi <Kumar>' };

  it('shows a pseudonym as the real name only when names are on and known', () => {
    expect(displayName('Student 1', table, true)).toBe('CE23B092');
    expect(displayName('Student 1', table, false)).toBe('Student 1');
    expect(displayName('Student 9', table, true)).toBe('Student 9');
  });

  it('replaces pseudonyms inside HTML, escaping the real name, and leaves longer numbers alone', () => {
    const html = '<b>Assistant 2</b> marked Student 1 and Student 12';
    expect(withNames(html, table, true)).toBe('<b>Ravi &lt;Kumar&gt;</b> marked CE23B092 and Student 12');
    expect(withNames(html, table, false)).toBe(html);
  });

  it('escapes every HTML-significant character', () => {
    expect(escapeHtml(`<a href="x">'&'</a>`)).toBe('&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;');
  });
});

describe('numbers read as people read them', () => {
  it('signs severities and treats rounding to zero as zero', () => {
    expect(signed(4.26)).toBe('+4.3');
    expect(signed(-1)).toBe('−1.0');
    expect(signed(0.04)).toBe('0.0');
    expect(signed(null)).toBe('–');
    expect(signed(Number.NaN)).toBe('–');
  });

  it('formats shares, p-values and marks', () => {
    expect(pct(0.336)).toBe('34%');
    expect(pValue(0.0004)).toBe('< 0.001');
    expect(pValue(0.0473)).toBe('0.047');
    expect(marks(7)).toBe('7');
    expect(marks(7.5)).toBe('7.5');
  });

  it('says severity in marks on a 10-mark question', () => {
    expect(inMarks(4)).toBe('0.4 marks less');
    expect(inMarks(-10)).toBe('1.0 mark more');
    expect(inMarks(0.2)).toBe('about the same');
  });

  it('sorts labels naturally and makes safe file names', () => {
    expect(['Assistant 10', 'Assistant 2'].sort(naturalCompare)).toEqual(['Assistant 2', 'Assistant 10']);
    expect(fileStem('CE3020 Jul–Nov 2026')).toBe('CE3020-Jul-Nov-2026');
    expect(fileStem('  ')).toBe('course');
  });
});

describe('a chosen file is checked before it is sent', () => {
  const file = (name: string, size = 10, type = '') => new File([new Uint8Array(size)], name, { type });

  it('accepts CSV and Excel workbooks', () => {
    expect(fileProblem(file('marks.csv'))).toBeNull();
    expect(fileProblem(file('marks.XLSX'))).toBeNull();
  });

  it('explains what to do with old spreadsheet formats, other files and empty files', () => {
    expect(fileProblem(file('marks.xls'))).toContain('.xlsx');
    expect(fileProblem(file('photo.png'))).toContain('Excel workbook');
    expect(fileProblem(file('marks.csv', 0))).toContain('empty');
  });
});

describe('progress stages follow the pipeline', () => {
  it('maps each reported stage to its step', () => {
    expect(stageIndex('Reading the file')).toBe(0);
    expect(stageIndex('Loading the answer-reading model')).toBe(3);
    expect(stageIndex('Reading the answers (512 of 2256)')).toBe(3);
    expect(stageIndex('Comparing answers across TAs, and the causal checks')).toBe(4);
    expect(stageIndex('Writing the report')).toBe(6);
    expect(stageIndex('Done')).toBe(7);
  });
});
