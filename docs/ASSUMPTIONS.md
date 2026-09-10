# Assumptions, and the knob that overrides each

The platform is meant for any IIT Madras instructor who divides marking among assistants, so
anything it assumes about a course is a limit on that claim. This file is the complete list.
It is short on purpose, and every row names the override.

| # | Assumption | Override | Status |
| --- | --- | --- | --- |
| A1 | The marks in the input are the marks the grade was computed from | none — it is the input | structural |
| A2 | A response is scored on an integer scale `0..max` | `CourseProfile.maxScorePerQuestion`; ordinal categories supported via `ScoreType.ORDINAL` | configurable |
| A3 | An assistant's severity is constant within one marking session | session facet; pooling across sessions tested by likelihood ratio, never assumed | tested |
| A4 | The cut-off rule is whatever the instructor says it is | `CourseProfile.cutoffPolicy`, `sBandPolicy` | configurable |
| A5 | Grade points follow R.21.1 (S=10 … E=4, U=0) | `LetterGrade` | to confirm against the printed ordinance table |
| A6 | A course grade is a weighted composite of assessments (R.19.1) | `CourseProfile.assessmentWeights` | configurable |
| A7 | Where each student is marked by exactly one assistant, the piles were alike in ability | not overridable — it is reported as `EstimableUnderExchangeability`, with the pile shape as evidence | reported, not hidden |
| A8 | Marking order, where recorded, is the order the scripts were marked in | drift is only fitted when a position column exists | configurable |

## Things we deliberately do not assume

- **That the Institute prescribes a marks-to-grades rule.** It does not. R.21.1 fixes the grade
  set and points, R.19.2 says markings are absolute, and R.22.1 with R.4.4(d) place the awarding
  of letters with the Class Committee. No formula appears anywhere in the Ordinances, and public
  accounts of the conventional bands contradict one another. So no rule is compiled in.
- **That a random split and a fixed roll-number split can be told apart by the audit.** They
  produce identical designs of identical rank. We report whether each pile is a contiguous block
  of roll numbers and leave the judgement to the reader.
- **That a re-grade is ground truth.** The literature motivating this project finds markers are
  not self-consistent over time, so the blind re-grade measures agreement between two noisy
  readings rather than convergence on a true score. Every result is framed that way.
- **That severity is a property of a person.** It is a property of a marking session, and every
  output is worded that way.
