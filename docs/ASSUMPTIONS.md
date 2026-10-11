# Assumptions, and the knob that overrides each

The platform is meant for any IIT Madras instructor who divides marking among assistants, so
anything it assumes about a course is a limit on that claim. This file is the complete list.
It is short on purpose, and every row names the override.

| # | Assumption | Override | Status |
| --- | --- | --- | --- |
| A1 | The marks in the input are the marks the grade was computed from | none — it is the input | structural |
| A2 | Each question is marked in steps no finer than a thousandth of a mark; each step it was actually marked in (whole, half, quarter…) is a category | a maximum column; `ScoreType.ORDINAL` for rubric bands | enforced — finer values are refused as computed, not awarded |
| A3 | An assistant's severity is constant across the course (all their marking sessions) | none yet; a per-session facet is future work | **stated** (10 Oct: the simpler estimator pools sessions) |
| A4 | The cut-off rule is whatever the instructor says it is | `CourseProfile.cutoffPolicy`, `sBandPolicy` | configurable |
| A5 | Grade points follow R.21.1: S=10, A=9, B=8, C=7, D=6, E=4, U=0 | `LetterGrade` | **verified** against the printed table in the 2015 B.Tech Ordinances |
| A6 | A course grade is a weighted composite of assessments (R.19.1) | `CourseProfile.assessmentWeights`; the same weights, times question maxima, decide exposure | configurable |
| A7 | Where each student is marked by exactly one assistant, the piles were alike in ability | not overridable — reported as `EstimableUnderExchangeability`, with roll-number evidence | reported, not hidden |
| A8 | Marking order, where recorded, is the order the scripts were marked in | drift is only audited when every mark has a position | configurable |
| A9 | What each question was marked out of is **declared**, not inferred | a maximum column in the CSV, or `CourseProfile.maxScorePerQuestion` | enforced — see below |
| A10 | Where a response was marked twice, the mark that counts is the one the `role` column says; with no such column, the first mark in the file | a `role` column (`record` / `second`); two marks of record are read as averaged | enforced, and warned (EH012) whenever it had to decide |
| A11 | A question belongs to its assessment: Q1 of a quiz and Q1 of the end-semester exam are different questions | none needed | structural |
| A12 | Severity acts additively (the estimator works in percentage points of each question's maximum; the benchmark generates marks on a logistic curve to test that this is good enough), and on the logit scale, so its effect on a student is, to first order, their exposure to each assistant times that assistant's severity | the exact statement for one marker of record per question needs only that each question's expected mark rises with ability | stated; nonlinearity is the estimator's to report |
| A13 | A student with no marks at all in an assessment was absent: they met no assistant there | none needed | enforced in `ExposureAudit.shares`; a missing *question* in a paper they sat is treated as structural, not absence |
| A14 | Where the audit says comparison rests on the piles being alike, that assumption is imposed by shrinking each student towards the class average (strength trained on the benchmark: 5 marks) | `Settings.studentShrinkage` | stated, and the re-check list is refused when piles follow the department code |
| A15 | The marks in a simulated course follow a logistic curve with reading noise, half-mark rounding and 1% absence | `CourseSimulator` | the benchmark's own assumption; it deliberately differs from the estimator's |
| A16 | In a one-row-per-student sheet, the TA named on a row marked every question on it, and a blank or `AB` cell is a question not answered | use the one-row-per-answer layout when TAs split a paper by question | stated on screen when the sheet is read (`WideSheet`); a second row for the same student and exam is read as a second marking |

## Three limits worth stating plainly

**The question maximum is never guessed.** Every estimate here is about where a mark sits between
nothing and full marks, so the denominator is not a detail: getting it wrong rescales a question's
difficulty and, through it, the severity of whoever marked it — silently, because the result still
looks reasonable. Taking the highest mark awarded as the maximum would be biased downward hardest
on exactly the questions nobody answered well. So the reader refuses a file whose marks are not all
0 or 1 when there is no maximum column, and warns when a question's declared maximum varies between
rows, which is the signature of a column holding a student's running total instead.

**No within-course arrangement identifies the team's collective severity.** Every severity is
measured against the panel. Under a relative rule that is all that matters; under an absolute rule a
uniformly harsh team lowers everyone, and only the instructor re-marking a sample can measure that.
The report says so rather than implying otherwise.

**Pass/fail courses are out of scope for grade-transfer analysis.** R.21.1 lists P (pass, with no
grade point at all) and F alongside the graded letters. A course marked P/F has no bands for a
correction to move a student between, so severity may still be estimable and worth reporting, but
the transfer counts and the shortlist are not meaningful and are not produced.

## Things we deliberately do not assume

- **That the Institute prescribes a marks-to-grades rule.** It does not. R.21.1 fixes the grade set
  and points, R.19.2 says markings are absolute, and R.22.1 with R.4.4(d) place the awarding of
  letters with the Class Committee. No formula appears anywhere in the Ordinances, and public
  accounts of the conventional bands contradict one another. So no rule is compiled in.
- **That a severity which cannot be estimated is a problem.** If every student's marks of record
  came from the same assistants in the same proportions, no severity can have moved anyone relative
  to anyone else. The audit reports that as safe, not as a failure.
- **That a random split and a fixed roll-number split can be told apart by the audit.** They
  produce identical designs of identical rank. Ingest reads the real roll numbers, while they are
  still in memory, and reports whether each pile is a contiguous block of roll numbers and whether
  piles follow the department code — as counts, never as anyone's place in the roll list. Contiguity
  is only assessed for assistants who marked part of the class, since an assistant who marked
  everybody has a trivially contiguous pile.
- **That a re-grade is ground truth.** The literature motivating this project finds markers are not
  self-consistent over time, so the blind re-grade measures agreement between two noisy readings
  rather than convergence on a true score. Every result is framed that way.
- **That severity is a property of a person.** It is a property of a marking session, and every
  output is worded that way.
- **That our standard errors are right because the formula says so.** The intervals are checked
  empirically by a coverage study rather than asserted.
