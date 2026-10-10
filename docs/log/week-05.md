# Weekly contribution log: Week 5 (10–16 Oct 2026)

Per the CS5013 project guide, section 10, item 5.

## Mohamed Zayaan S (CE23B092)

**The rework (10 Oct).** Weeks 1–4 produced no commits, and nothing ran end to end; the estimator in
the design (marginal-ML Rasch, in logits) was the largest unbuilt piece. I re-planned around one question: what is the simplest system that answers the stakeholder's four
questions in marks, and can be shown to be right? With an AI assistant (Claude Code), using the
prompts now in `prompts/`, the system now runs end to end:

- `eh-estimate`: an additive model in percentage points, fitted by backfitting (repeated weighted
  averages), with a student bootstrap and a no-effect test that keeps the allocation fixed.
- `eh-outcome`: IITM grading rules, grades as marked vs. corrected, and the re-check list behind the
  gate pre-registered in the design document.
- `eh-report`: the instructor report and an aggregate-only Class Committee sheet, as HTML.
- `eh-app`: the CLI, a browser page on 127.0.0.1 for the stakeholder, the Simulation Lab, and the
  benchmark.
- `CourseSimulator`: seven IITM course shapes with known answers. This is the dataset: 168 training
  and 112 test courses. The two shrinkage constants were trained on the first set; everything was
  measured once on the second.

**Defects found by the benchmark, each fixed under a regression test:** one absentee made every
course "unmeasurable"; the no-effect test called a lucky tutorial group a harsh TA (4/4 false
alarms); department-aligned piles gave severities 4 points off, so their re-check list is now
refused. Found while testing the browser page: an absent form field crashed the request.

**Cut, with reasons** (`docs/ARCHITECTURE.md`): `eh-linking`, Spring/SQLite, the logit-scale estimator.

## Pruthviraj Milind Chougale (CE23B104)

*To be completed by Pruthviraj.* Ownership of `eh-outcome`, `eh-report` and `eh-app` stands in
CODEOWNERS; this week's code in those modules was written in the rework above and needs his review
and his own commits before the evaluation.

## Gaps, honestly

- No real marks yet: every result is on synthetic courses. The allocation table from the
  stakeholder's course is still the first thing to obtain; the audit needs no marks.
- The git history shows one author. That is a rubric item we currently fail.
- Severity is pooled across marking sessions (ASSUMPTIONS A3); drift is audited but not estimated.
- On department-aligned piles the estimate is unreliable by construction; the tool refuses, it does
  not fix.
