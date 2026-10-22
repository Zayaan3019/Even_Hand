import type { Result } from '../api';

/** A result in the server's exact shape (eh-app ResultJson), for component tests only. */
export function fixture(): Result {
  return {
    course: { name: 'CE3020 <Test>', students: 120, tas: 2, questions: 3, answers: 360, keys: 3 },
    verdict: {
      kind: 'from-answers',
      headline: 'Every assistant can be placed on one scale by comparing answers that say the same thing.',
      exposure: 'Students met different mixes of assistants.',
      exposureStatus: 'ESTIMABLE',
      fromMarking: 'Assistants can be compared, but only on the assumption that their piles were alike.',
    },
    basis: 'answers',
    confirmedByAnswers: false,
    choice: 'The answers show the piles were not alike (Hausman test p = 0.004). The answers\' estimate is used.',
    bottomLineHtml: 'The TAs did not mark alike. Assistant 1 was the harshest. It changes <b>3</b> letter grades.',
    tas: [
      { label: 'Assistant 1', marks: 180, severity: 5.2, lo: 2.1, hi: 8.3, se: 1.5, group: 1, clear: true },
      { label: 'Assistant 2', marks: 180, severity: -5.2, lo: -8.3, hi: -2.1, se: 1.5, group: 1, clear: true },
    ],
    noEffect: { p: 0.004, simulations: 200, spread: 5.2 },
    crossCheck: {
      otherName: 'From the marks alone (assumes piles were alike)',
      rows: [
        { label: 'Assistant 1', used: 5.2, other: 1.0, lo: -1, hi: 3 },
        { label: 'Assistant 2', used: -5.2, other: -1.0, lo: -3, hi: 1 },
      ],
    },
    content: {
      status: 'USABLE', embedder: 'all-MiniLM-L6-v2', semantic: true, textCoverage: 1, signal: 0.38, matchedShare: 0.7, p: 0.004,
      notes: [],
      questions: [{ label: 'Q1', answers: 120, signal: 0.4, pairs: 300, tas: 2 }],
      severity: [
        { label: 'Assistant 1', severity: 5.2, lo: 2.1, hi: 8.3, pairs: 300 },
        { label: 'Assistant 2', severity: -5.2, lo: -8.3, hi: -2.1, pairs: 300 },
      ],
      sameAnswer: [{
        question: 'Q1', max: 10, similarity: 0.96, gap: 40,
        first: { student: 'Student 3', ta: 'Assistant 2', marks: 8, excerpt: 'The moment is maximum where shear is zero.' },
        second: { student: 'Student 7', ta: 'Assistant 1', marks: 4, excerpt: 'Moment is max where the shear force is zero.' },
      }],
      sameTa: [],
      outOfLine: [{ question: 'Q2', max: 10, student: 'Student 9', ta: 'Assistant 1', marks: 0, expected: 8, z: -4.2, excerpt: 'A full answer.' }],
      causal: {
        variance: { content: 40, ta: 12, residual: 48 },
        decomposition: [
          { ta: 'Assistant 1', answers: 180, raw: 2, content: 7, marking: -5.2, unexplained: 0.2 },
          { ta: 'Assistant 2', answers: 180, raw: -2, content: -7, marking: 5.2, unexplained: -0.2 },
        ],
        overlap: [
          { ta: 'Assistant 1', matchedShare: 0.8, before: 0.6, after: 0.05, length: 0.02 },
          { ta: 'Assistant 2', matchedShare: 0.75, before: -0.6, after: -0.04, length: -0.03 },
        ],
        placebo: { p: 0.01, shuffles: 99, observed: 5.2, shuffled: 0.9 },
        sensitivity: [{ ta: 'Assistant 1', pairs: 150, direction: 1, p: 0.0001, gamma: 2.4 }],
        mechanisms: [
          { ta: 'Assistant 1', kind: 'CONSISTENT_SEVERITY', heterogeneityP: 0.5, slips: 1,
            explanation: 'Assistant 1 marked harsher than the others on answers saying the same thing.',
            byQuestion: [{ question: 'Q1', severity: 5, se: 1, pairs: 100 }, { question: 'Q2', severity: 5.5, se: 1, pairs: 100 }] },
          { ta: 'Assistant 2', kind: 'NO_CLEAR_DIFFERENCE', heterogeneityP: null, slips: 0,
            explanation: "Assistant 2's marking is within what chance produces.", byQuestion: [] },
        ],
      },
    },
    outcome: {
      share: 3.1, changed: 3, low: 1, high: 5,
      grades: [{ grade: 'S', asMarked: 10, corrected: 11 }, { grade: 'A', asMarked: 30, corrected: 29 }],
      recheck: [{ student: 'Student 7', total: 59.5, corrected: 61.2, grade: 'C', likely: 'B', probability: 0.8, points: 1 }],
      expectedFalseFlags: 0.2,
    },
    diagnostics: [{ severity: 'note', message: 'An answer key was found for 3 question(s).', fix: '', more: 0 }],
    footnotes: ['answers read by: all-MiniLM-L6-v2'],
    manifest: { version: 'development build' },
    instructorHtml: '<html><body>Assistant 1</body></html>',
    committeeHtml: '<html><body>aggregate</body></html>',
    names: { 'Assistant 1': 'Ravi', 'Assistant 2': 'Meena', 'Student 3': 'CE23B003', 'Student 7': 'CE23B007', 'Student 9': 'CE23B009' },
  };
}
