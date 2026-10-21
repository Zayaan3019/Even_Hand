# Even Hand: architecture as built

*Revised 10 October 2026. The design document (11 September) is unchanged in [`DESIGN.md`](DESIGN.md);
this file describes the system as it now runs and records every place it departs from that design,
and why.*

## The one-sentence version

A marks CSV goes through five stages: **ingest → audit → estimate → outcome → report**. Each stage
is one Maven module and one entry point. The audit can stop the pipeline after stage 2 and say why.

```
                          ┌───────────────────────────── refused: what is wrong, and the repair ───────┐
                          │                                                                             ▼
 marks.csv ──► 1 INGEST ──► 2 AUDIT ──────► 3 ESTIMATE ──────────► 4 OUTCOME ─────────► 5 REPORT ──► report.html
 roll_no,     CsvIngest    Identifiability  SeverityEstimator      Outcome.assess        HtmlReport     committee.html
 assessment,  pseudonymise Audit.audit      backfit + bootstrap    GradingRule.grade    instructor +
 question,    validate     (no marks read)  + no-effect test       re-check gate        committee
 max, marks,   │            │                │                      │
 ta, role      ▼            ▼                ▼                      ▼
          IngestResult   AuditVerdict    FittedModel ────────►  Outcome.Result
          (responses,    (sealed: four   (the seam: severities,  (grades as marked vs
           design, key)   cases + exposure) intervals, p-value)   corrected, re-check list)
```

`Pipeline.run` (in `eh-app`) is exactly this diagram, one line per stage, plus stage 3b: the written
answers are embedded (`eh-embed`) and compared across TAs (`eh-content`), and a Hausman test decides
which estimate corrects the grades (`ContentFit.choose`; see [METHOD.md](METHOD.md)). Two front ends
call it:

| Front end | Class | For |
| --- | --- | --- |
| Browser page, `even-hand.cmd` | `LocalServer` + `upload.html` (JDK `HttpServer` on 127.0.0.1) | The instructor, in three steps: drop the marks file, .xlsx or .csv (`/inspect` says what was read and what to fix), set the weights, read the report (`/analyse` starts a job the page polls on `/job`). Real names are shown on screen only; saved copies carry pseudonyms. Nothing is written to disk. |
| Command line, `run` | `Main` | Scripting and batch runs |

The simulator and the benchmarks are not part of the product: the simulator is test code
(`eh-design`'s test-jar) and the benchmarks live in `eh-validation`, which is never packaged.

## Stage by stage

| # | Module · class | Input → output | The idea, in one line |
| --- | --- | --- | --- |
| 1 | `eh-ingest` · `CsvIngest.read` | CSV → `IngestResult` | Either one row per answer, or the usual one row per student with `Q1 (10)` headers (`WideSheet` unpacks it, keeping line numbers); columns found by header name; roll numbers and TA names become keyed pseudonyms before any `Response` exists; every bad row is reported with its line number. |
| 2 | `eh-design` · `IdentifiabilityAudit.audit` | `MarkingDesign` (no marks) → `AuditVerdict` | From *who marked what* only: which TAs can be compared (exact integer rank of the design matrix), and whether any student met a different mix of TAs than another (**exposure**). |
| 3 | `eh-estimate` · `SeverityEstimator.fit` | `AnchoredDesign` + responses → `FittedModel` | `mark% = mean + ability + easiness − severity`, fitted by backfitting; 95% intervals by resampling students; p-value by re-simulating the course with identical TAs on the same allocation. |
| 4 | `eh-outcome` · `Outcome.assess` | `FittedModel` + course rule → `Outcome.Result` | Grade the course twice under its own rule: as marked, and with each mark given back its TA's severity. Repeat over bootstrap draws to get the chance each grade changes. |
| 5 | `eh-report` · `HtmlReport` | everything → two HTML files | Instructor report (per-TA, with intervals, and the re-check list); Class Committee sheet (aggregates only). |

### Stage 2's four verdicts, and what happens next

| Verdict | Meaning | Pipeline |
| --- | --- | --- |
| `Estimable` | Some scripts were double-marked, or marking rotates, so every TA is on one scale from the marking alone | Estimate; re-check list if the no-effect test rejects |
| `EstimableUnderExchangeability` | Each student had one TA (script-wise piles): comparable only if the piles were alike | Estimate, with student shrinkage as the stated assumption; **refuse** the list if piles follow department codes |
| `PartiallyEstimable` | TAs fall into groups comparable within, not across | Estimate within groups |
| `NotEstimable` | E.g. one TA per question | Stop. If exposure is `NONE`, the course is **safe by design**: a harsh TA is indistinguishable from a harder question and moved nobody relative to anybody |

## The estimator, for the whiteboard

Every mark is turned into a percentage of its question's maximum, `y`, and weighted by the
question's maximum, `w`, so a 20-mark answer counts 10× a 2-mark one. Then repeat until nothing
moves by more than 0.00001:

```
ability[s]  = Σ w (y − mean − easiness[q] + severity[t]) / (Σ w + kStudent)    over student s's marks
easiness[q] = Σ w (y − mean − ability[s]  + severity[t]) /  Σ w                   then centre on 0
severity[t] = Σ w (mean + ability[s] + easiness[q] − y) / (Σ w + kTA)          then centre on 0 per group
mean        = Σ w (y − ability − easiness + severity) / Σ w
```

That is all of `Backfit.java`. `kTA = 50` marks shrinks a TA who marked little towards the panel.
`kStudent = 5` is applied **only** when the verdict rests on the piles having been alike, because
then pulling each student towards the class average *is* that assumption. Both constants were
trained on the benchmark ([`BENCHMARK.md`](BENCHMARK.md)).

**Uncertainty.** Resample the students with replacement, refit, 200 times; an interval is the
2.5th–97.5th percentile. Resampling whole students captures the main uncertainty in script-wise
marking: that one TA's pile happened to be stronger.

**No-effect test.** Simulate the course 99 times with every TA identical, on the *same*
allocation, with the students dealt to it afresh and noise drawn from each question's own
residuals, and count how often the spread of severities reaches the observed one. (Shuffling the
TA labels instead, as the proposal planned, would turn a question-wise design into a crossed one
and test the wrong thing.)

## The seam

`FittedModel` (in `eh-core`) is the only thing stage 3 hands to stage 4: severities, their
bootstrap draws, groups, the p-value. Nothing downstream reaches back into how the fit was made,
so the estimator can be replaced without changing what the instructor is told. ArchUnit asserts
the direction: `estimate` may not depend on `outcome`, `report` or `app`; `design` may not depend
on any later stage.

## Invariants, each enforced by a type or a test

1. **No estimate without an audit of this design.** `SeverityEstimator.fit` only accepts an
   `AnchoredDesign`, whose factory refuses `NotEstimable` and re-verifies the certificate against the
   design in hand (`AnchoredDesignTest`).
2. **No corrected mark becomes a grade.** The only per-student output is a recommendation to
   re-check; the report says so (`HtmlReportTest`).
3. **Refusal is a value.** `Outcome.Shortlist` is sealed: `Emitted` or `Refused(reason)` (`OutcomeTest`).
4. **No identifier on disk** except in the sealed key file (`PipelineTest.reportsCarryNoIdentifiers`,
   `CliTest`, `CsvIngestTest`).
5. **Nothing leaves the machine.** Only `LocalServer` listens, on loopback; no class is a network client (`ArchitectureTest.noNetwork`, `LocalServerTest.loopbackOnly`).
6. **Per-TA figures reach only the instructor**; the committee sheet names no TA or student
   (`HtmlReportTest.committeeSheetIsAggregateOnly`).
7. **Reproducible:** every random draw is seeded per draw, so parallel threads give identical
   results (`SeverityEstimatorTest.reproducible`).

## Where this departs from the design document, and why

| Design (11 Sep) | As built (10 Oct) | Why |
| --- | --- | --- |
| Many-facet Rasch, marginal ML via EM over Gauss–Hermite quadrature, in logits | Additive model in **percentage points**, backfitted, with shrinkage | The instructor grades in marks, and the acceptance criterion was "severity reported in marks". The benchmark (data generated from a *logistic* model the estimator does not assume) shows ~0.5-point error where the marking links the TAs. Every line can be explained at a whiteboard. |
| Parametric bootstrap from the covariance | Student bootstrap | Captures pile-composition luck directly; no covariance to derive. |
| `eh-linking` optimiser | Cut; the audit's diagnostics name the repair (e.g. "double-mark ten Q1 scripts") | The cut line in §4 of the design allowed it. One module fewer to own and explain. |
| Spring Boot + Thymeleaf + SQLite web app | The JDK's built-in HTTP server on 127.0.0.1 plus a CLI; reports are self-contained HTML | The stakeholder double-clicks one file and uses his browser. No framework to explain, no database holding marks, nothing to host. |
| Severity per (TA, session) | Per TA across the course | Simpler; pooling across sessions remains a stated assumption (ASSUMPTIONS A3). |

## Defects the benchmark found, each now a regression test

1. **One absentee made every real course "unmeasurable".** An absent student's row of exposure
   was empty, so it differed from everyone's, and the audit refused every re-check list. Fixed in
   `ExposureAudit.shares` (whole-assessment absence takes the class's mix);
   `ScenarioMatrixTest.anAbsenteeIsNotDifferentlyExposed`.
2. **The no-effect test called a lucky tutorial group a harsh TA**, in 4 of 4 such courses. The
   null worlds kept every student in their real pile. Fixed by dealing students afresh in each null
   world; `SeverityEstimatorTest.noEffectTestAllowsForTheLuckOfTheDeal`.
3. **Department-aligned piles give severities about 4 points off** (LAB_BATCHES in the benchmark),
   because severity cannot be told from the department a TA marked. The re-check list is now refused
   there, with the repair named, and such courses are kept out of training;
   `PipelineTest.departmentPilesAreRefused`.
