# Even Hand — Design Document

**A Grading-Consistency Audit for IIT Madras Courses**
Mohamed Zayaan S (CE23B092) · Pruthviraj Milind Chougale (CE23B104) — Team *God's Eye*
11 September 2026 · Repository: <https://github.com/Zayaan3019/Even_Hand>

> This is the repository-readable mirror of `DESIGN.tex`, which is the submitted PDF source.
> Where the two differ, the `.tex` is authoritative.

---

## 1. What changed since the proposal

Scope was approved unchanged, so this revision is driven by what we learned after 1 September.

**(a) The Institute prescribes no marks-to-grades rule.** The *B.Tech Ordinances* (2015) fix grades
and points (R.21.1), make marking absolute (R.19.2) and leave letters to the Class Committee
(R.22.1, R.4.4(d)); no mean, standard deviation, top share or cut-off appears. The bands our
proposal called institute policy are departmental convention, so the rule is per-course
configuration.

**(b) Grade points are unevenly spaced** (S=10, A=9, B=8, C=7, D=6, **E=4**, U=0; a U in a core
course must be repeated, R.25.1), so the shortlist ranks by expected grade-point change,
`P(grade changes) × Δ points`, not by probability alone.

**(c) Severity harms a student only through *differential exposure*** (§2.3). When each question's
marks of record come from one assistant, a harsh assistant is indistinguishable from a harder
question *and moves nobody relative to anybody*. Pure question-wise marking — the proposal's
"failure" case, and our stakeholder's arrangement — is therefore *safe by design*, and the rotation
we meant to recommend can *create* the harm it then has to measure. The plan changes accordingly (§4).

---

## 2. Architecture

### 2.1 One pipeline, nine modules, one seam

A course enters at `eh-ingest` and leaves as a report from `eh-report`; `./mvnw verify` builds and
tests all of it. Dependencies flow strictly downward, and `eh-estimate`'s POM does not offer Spring,
JPA or SQLite, so "the estimator is tested without a database" is a property of the build graph.
ArchUnit asserts the same rules; `.github/CODEOWNERS` maps each module to its owner.
† marks a signature fixed here whose implementation is planned.

| Module | Stage | Exports across its boundary | Owner |
| --- | --- | --- | --- |
| `eh-core` | IR, seam | `MarkingDesign` (cannot hold a score; carries `AllocationEvidence`); sealed `AuditVerdict` with its `Exposure` and `IdentifiabilityCertificate`; `AnchoredDesign.of(design, verdict, anchoring)`; `FittedModel`; `CourseProfile`, every field `Provenanced` | Zayaan |
| `eh-numerics` | arithmetic | `ExactRowSpace` (integer rank, row-space membership, null basis); `SymmetricEigen`; Cholesky†; seeded RNG† | Zayaan |
| `eh-ingest` | parser | `CsvIngest.read(csv, pseudonymiser)` → responses, design, line-numbered diagnostics; `KeyFile.seal`, `KeyFile.open` | Zayaan |
| `eh-design` | analysis | `IdentifiabilityAudit.audit(design, weights)` → `AuditVerdict`; `DriftAudit.audit(design)`; `DesignProfiler.profile(design)` | Zayaan |
| `eh-estimate` | measurement | `Estimator.fit(anchoredDesign, responses)` → `FittedModel`†; `NullTest`†; `UncertaintyEngine`† | Zayaan |
| `eh-linking` | fix-it hint | `LinkingDesign.propose(design, exposure, targetSE)` → ranked changes† | Pruthviraj |
| `eh-outcome` | consequence | sealed `GradingRule`†; `Outcome.of(model, profile)` → transfers and a sealed `Shortlist`, `Emitted` or `Refused`† | Pruthviraj |
| `eh-report` | narration | Instructor report, one-page Class Committee sheet, `RunManifest`† | Pruthviraj |
| `eh-app` | runtime | Spring Boot and Thymeleaf on 127.0.0.1; JPA/SQLite run store; CLI† | Pruthviraj |

**The seam.** Everything the measurement stage knows leaves it in one `FittedModel` (estimates,
covariance on the identified subspace, anchoring, verdict); nothing downstream reaches back, so the
estimator can be replaced without changing what the instructor is told. Its only entry point takes
an `AnchoredDesign`, whose single factory refuses a NOT-ESTIMABLE verdict or a certificate that is
not true of the design in hand, checked at use because the audit runs weeks before the marks exist.

### 2.2 Trust invariants

Each is enforced by a type, the build graph or a named test — never by convention.

1. **No estimate without an audit of *this* design** (`AnchoredDesign`). The certificate is
   verified, not trusted: the rank of the information matrix modulo two primes bounds its claimed
   rank from below, and a null basis that every marking annihilates bounds it from above.
2. **No corrected mark ever becomes a grade.** Counterfactual marks stay inside `eh-outcome`; the
   only per-student output is a recommendation to re-check a script, and a grade changes only by a
   human re-mark (ArchUnit: no export path).
3. **Refusal is a value**: NOT-ESTIMABLE stops estimation, and a shortlist that fails its gate is a
   `Refused` value carrying its reason.
4. **Every figure carries its interval, every input its provenance** (measured, declared or defaulted).
5. **No identifier on disk except in the key file, which is sealed first; no pseudonym encodes order**
   (labels in keyed-digest order).
6. **Nothing leaves the machine**: loopback bind; ArchUnit forbids HTTP clients and sockets.
7. **Per-assistant figures reach only the instructor**; the committee sheet is aggregate.
8. **Reports reproduce byte-for-byte** from their manifest and key, using a seeded RNG, sorted
   iteration and `StrictMath`.

### 2.3 The audit: who could have been harmed, and can it be measured?

With `η_nij = θ_n − δ_i − γ_j` (ability, difficulty, severity), the audit answers three questions
from the allocation alone. **It reads no marks**, so the instructor has the answers before sharing
any data; this is what we bring the stakeholder in week one.

**1. Exposure: could severity have moved anyone?** Let `E_nj` be the share of student *n*'s
composite (question maxima × assessment weights, marks of record only) awarded by assistant *j*.
With one marker of record per question, each question's expected mark rises with ability under the
same assistant for everyone, so the expected composite rises with ability *whatever the
severities*: rank-based grades are unchanged in expectation, and sigma bands move only as they would
for a harder paper. In general the harm is, to first order, `Σ_j (E_nj − Ē_j) s_nj γ_j` with `s` the
local marks-per-logit slope: zero for exposure-balanced rotation, largest for script-wise piles.
Summed over assessments, it answers the proposal's allocation-regime question before anything is
estimated.

**2. Estimability: can what matters be separated?** For each pair of assistants we test membership
of the design's row space by fraction-free integer elimination — a fact about the arrangement, not a
floating-point threshold (Facets' graph heuristic is documented to misreport "exotic forms of
connectedness"). A harm contrast `(E_n − E_m)·γ` is then estimable exactly when every student
carries the same weight inside every group of mutually comparable assistants. Script-wise and
question-wise marking are *equally* rank-deficient (excess null dimension `J−1` beyond the two that
anchoring removes) but differ in kind. Question-wise, severity is confounded with difficulty and
nothing about students separates them. Script-wise, it is absorbed into the pile's abilities, and
is recoverable if the piles were alike. Re-asking against a design whose student columns are
collapsed to one intercept tells the two apart — and that collapsed design is exactly the
fixed-effect part of the estimator's random-ability model (§2.4), so the audit certifies what the
estimator will assume. Random and roll-number splits are the same design, so the audit reports
evidence instead: whether piles are contiguous in roll number or follow its department code,
computed at ingest from the real roll numbers and kept only as counts. A 400-student course audits
in about a second.

**3. Drift: does a late script lose?** The same test, with marking position as a column, exposes a
trap. If every marker works through the stack in the same (usually roll-number) order, the same
students are late on *every* question, so the harm accumulates, yet drift is identified only by
assuming stack order is unrelated to ability. Having alternate markers reverse the stack identifies
drift outright and balances its exposure, at no cost. Under our stakeholder's arrangement, drift,
not severity, is the harm that can exist.

**The verdict** names the groups of mutually comparable assistants — a partition, so two
equal-sized groups are both reported — summarised as ESTIMABLE, UNDER-EXCHANGEABILITY, PARTIAL or
NOT-ESTIMABLE, and every verdict carries its exposure. Without differential exposure the course is
**safe** whatever the verdict, and `EH003` is a note, not an error. With it, the harm is
**correctable** if estimable (stated as resting on alike piles when under exchangeability) and
otherwise **harmful and unmeasurable**: the tool refuses and names the repair.

**What changes in the advice.** The linking design optimises, in order: (1) no differential
exposure — one marker of record per question, or rotation only in exposure-balanced form (rotating
over questions worth 5/10/15/10/10 marks leaves up to 5 of a student's 50 marks with a different mix
of assistants from the class's); (2) a small double-marked sample, which measures inconsistency and
anchors drift; (3) rotation for per-assistant feedback, only if the instructor wants it. No
within-course arrangement identifies the team's *collective* severity; under an absolute rule that
needs the instructor to re-mark a sample, and the report says so.

### 2.4 Estimation and uncertainty

**Model.** Many-facet Rasch, fitted by *marginal* maximum likelihood (EM over Gauss–Hermite
quadrature, abilities a normal random effect); PROX and joint ML supply only start values. Joint ML
was dropped as the estimator: its incidental-parameter bias needs a correction Facets leaves manual;
it offers no shrinkage at 60–150 students; and it cannot see that piles differ by chance — at 30
scripts per pile and an ability SD of 1 logit, pile means differ with SD 0.26 logits, two-thirds of
the proposal's own 0.4-logit example — so its intervals under exchangeability would be confidently
wrong. Scores are modelled in the steps ingest reads (halves, quarters); long scales use Andrich's
two-parameter dispersion thresholds, so a 20-mark question costs two parameters, not twenty.
Severity is estimated per (assistant, session), with pooling tested by likelihood ratio. Erratic
marking and central tendency are reported, never folded into severity, and each question's
effective weight in the ranking is shown against its nominal weight.

**Uncertainty.** One engine pushes parameter draws (parametric bootstrap for headline figures)
through the composite (R.19.1), boundaries, grades and transfers, so every interval comes from one
tested component. **The no-effect test preserves the design**: it simulates the fitted no-severity
model on the *same* allocation and refits. Permuting grader labels, as the proposal planned, would
turn a question-wise design into a crossed one and test the wrong null. The negative control puts
the real allocation's labels on an auto-scored component.

### 2.5 Consequence, and privacy

`GradingRule` is sealed (sigma-band, top-fraction, absolute, instructor-band; explicit
`sBandPolicy`). Counterfactual marks are referenced to the panel-average assistant, and boundaries
are recomputed on them. Hand-drawn bands have no knowable counterfactual, so we report the range
between holding the thresholds and holding the grade counts. The shortlist gate is pre-registered
in week 4. A shortlist is emitted only if the no-effect test rejects at 5%. Each row needs
`P(grade changes) ≥ 0.2`, and there are at most 20 rows, ranked by expected grade-point change,
with the expected number of false flags printed. Rows run in both directions; under a fixed share,
each promotion names the script it displaces. Every row carries the R.24.1 revision deadline.

**Privacy and security.** Threats: a lost laptop, re-identification, appraisal misuse,
exfiltration. Identifiers become HMAC-SHA-256 digests under a course-scoped salt, so a course's
assessments link but a new offering does not. Review found two gaps, both now fixed. Labels were
issued in file order, so in a roll-sorted export "Student 1" was the lowest roll number; they now
follow keyed-digest order. And the key file, the only re-identification table, was plain text; it
now carries the course salt and is sealed with AES-256-GCM under a passphrase (PBKDF2, 600,000
iterations, header authenticated; JDK only) before it is written. Pseudonyms do little for
assistants where the allocation is known ("the Q3 marker" is a name), so per-assistant figures go
only to the instructor. Minimal fields, local processing and deletion on request follow the
data-sharing note and the DPDP Act, 2023.

---

## 3. Test plan

At least one named test per module. Test dependencies are test-scope only, so no third-party
numerics reach production. **104 tests pass today**, one of them a property checked on 150 random
allocations, all in the four implemented modules; the other five have the signatures above and no
implementation yet, so their tests are *planned*.

| Module | Tests (**passing** today; *planned*) |
| --- | --- |
| `eh-core` | **passing** — a certificate is bound to its design, and one claiming more identification, or more confounding, than the marking supports is rejected; `AnchoredDesign` refuses NOT-ESTIMABLE; a verdict cannot over- or under-claim its certificate; the fingerprint ignores row order but not marking order or which mark counts |
| `eh-numerics` | **passing** — rank vs hand-computed matrices; duplicate rows never inflate rank; exact membership; null vectors annihilated; eigenvalue count equals exact rank. *Planned*: Cholesky and the seeded RNG, with the estimator |
| `eh-ingest` | **passing** — **no roll number or assistant name survives ingest**; labels independent of row order; pile evidence read from roll numbers, not rows, including department codes; half and quarter marks; computed values refused; the mark of record by role column, first mark or average; questions scoped to their assessment; the key sealed, a wrong passphrase and tampering refused, the course salt restored; no maximum means refusal; a totals column caught; bad rows reported by line |
| `eh-design` | **passing** — the scenario matrix (Appendix A) *including refusals*, with exposure and drift per row; exposure as a property of 150 random allocations; `EH003` a note when harmless and an error when not; equal-sized groups both reported; the three drift cases; on every scenario, exact and eigenvalue null dimensions agree and the certificate survives re-verification; the profiler recovers the arrangement unaided |
| `eh-estimate` | *planned* — parameter recovery; **95% coverage**, including script-wise designs with abilities redrawn each replication; the no-effect test has 5% size; partial credit reduces to dichotomous at m=1; anchoring invariance; monotone EM likelihood; **negative control** inside the null |
| `eh-linking` | *planned* — connectivity repair minimal (`c−1` links); an exposure-balanced rotation found when one exists; never proposes more differential exposure than the current design |
| `eh-outcome` | *planned* — rules vs hand-computed cohorts; fixed-share exchange (#promoted = #displaced) as a property; instructor-band range brackets both limits; shortlist symmetric and refused in ≥95% of no-effect simulations |
| `eh-report` | *planned* — no key-file name in any output; no per-assistant figure on the committee sheet; R.24.1 date; golden snapshots |
| `eh-app` | *planned* — loopback bind; no-network rule; no export path from counterfactual marks; end-to-end over HTTP; full run at N=150 under 60 s |

**Defects found in review, now fixed**, each under a regression test: pseudonyms revealed file
order; the roll-number evidence read row order, so an export sorted by assistant looked like a
roll-number split; half marks failed to parse; the mark of record for a double-marked unit was
undefined; two equal-sized groups of assistants were reported as one reachable and one blocked; the
exact-versus-eigenvalue cross-check covered one unit matrix; and, found while fixing these, Q1 of a
quiz and Q1 of the end-semester exam were merged into one question.

---

## 4. Revised milestone plan

- **R1. The audit moves ahead of the marks**, and already gives three answers from the allocation
  alone: exposure, estimability and drift estimability. Week 1 runs it on the real one.
- **R2. A prediction replaces a risk.** If the allocation table confirms one marker of record per
  question, we pre-register about zero severity-driven transfers on the first course. Drift joins
  the core wherever marking order is recorded or can be declared.
- **R3. The second real course is requested in week 1, not week 7**, preferably script-wise, where
  transfers are expected.
- **R4. Precision is the binding risk.** The design-only standard-error forecast gates the
  shortlist; the mid-demo criterion is "estimated, or refused with the repair named".
- **R5. Marginal ML replaces JMLE plus manual bias correction.** Plan B is JMLE with pile means
  constrained and pile variance added analytically, behind the same seam, chosen at the week-3 gate.
- **R6. Institutional fit**: configurable cut-off rule (§1(a)), composites (R.19.1), and outputs
  timed to the Class Committee and the R.24.1 window.
- **R7. Workload rebalanced.** Week 0's commits are one member's, and we say so. The seam lets
  `eh-outcome` start on a synthetic fit, so Pruthviraj leads a deliverable every week and both of us
  commit weekly.
- **R8. The cut line has dates**: question diagnostics on 2 Oct, the linking optimiser on 23 Oct,
  drift only if no marking order exists. The audit, exposure analysis, estimator, propagation and
  blind re-grade are never cut; the re-grade's pre-registration is committed in week 4.

| Wk | Dates | Zayaan | Pruthviraj |
| --- | --- | --- | --- |
| W0 | 10–11 Sep | **done**: build, seam, exact audit, generator, profiler, ingest; review fixes (certificate verification, exposure and drift audits, keyed pseudonyms, sealed key); 104 tests | Review of week-0 commits |
| W1 | 12–18 Sep | Allocation table → exposure, verdict, drift and SE forecast to the stakeholder | `GradingRule` family and composite on a synthetic fit; second course requested |
| W2 | 19–25 Sep | Marginal ML (binary), standard errors, recovery test | Transfers; shortlist gate; instructor-band range |
| W3 | 26 Sep–2 Oct | Partial credit, dispersion thresholds, uncertainty engine; gate | CLI end to end on a stub fit; committee sheet; golden tests |
| W4 | 3–8 Oct | Design-preserving null; coverage study; drift if order known | Thymeleaf views; run store; pre-registration (both) |
| — | **9 Oct** | **Mid-demo**: the stakeholder's course end to end — exposure, verdict, estimates or refusal, grades under the confirmed rule; gap list | |
| W5 | 10–16 Oct | Sessions, rater diagnostics, effective weights | Variance share; transfers with intervals; shortlist on real data |
| W6 | 17–23 Oct | Negative control; second course fitted | Exposure-balanced linking optimiser; second-course report |
| W7 | 24–30 Oct | Hardening; performance guard | Blind re-grade issued (masked scans); handoff pack and installer |
| W8 | 31 Oct–5 Nov | Re-grade analysis; stakeholder dry-run; cross-module viva preparation (both) | |
| — | **6 Nov** | **Final submission and viva** | |

---

## 5. Risks and plan B

1. **The first course shows no severity harm.** Now expected and pre-registered (R2); a correct
   "safe by design" is a finding. Transfers are shown on the second course or, failing that, on the
   stakeholder's own allocation with injected, known severities, labelled synthetic.
2. **Nobody recorded who marked what**, as is usual with paper scripts. Intake will accept the
   allocation as rules ("TA 2: Q3–Q4, all students"; "TA 1: all questions, CE23B001–060"), with a
   template to use before the next exam is marked.
3. **Marking order was not recorded.** The instructor may declare roll-number order (flagged
   DECLARED); recording it at the end-semester exam costs nothing.
4. **Intervals are too wide, or marginal ML slips.** We report the intervals and refuse the
   shortlist, as a type; the estimator falls back to plan B (R5) at the week-3 gate.
5. **No effect is detected.** We report it, with the effect we can rule out and the sample needed;
   we do not shop for another course.
6. **An assistant's standing is harmed.** Per-assistant figures go to the instructor only; erratic
   marking is reported apart from harsh; estimates describe a marking session, not a person.
7. **The blind re-grade is not blind**: returned scripts carry the assistant's ink, so scans are
   masked before they go back (else ten flagged and ten control scripts, else a reported gap). The
   test is directional, with its power pre-registered.

---

## Appendix A — the scenario matrix

Output of `AuditReport`, run by CI on every commit; every column is computed by the audit and
asserted in the tests, and each answer was worked out on paper first. Exc.: null dimension beyond
the two anchoring removes. Question maxima are equal except where stated. No row uses a mark.

| Arrangement | Exc. | Severity | Exposure | Drift | Headline |
| --- | --- | --- | --- | --- | --- |
| Question-wise + double-marking | 0 | ESTIMABLE | none | not recorded | Safe; the stakeholder's course |
| Question-wise, marker rotated | 0 | ESTIMABLE | none | not recorded | Safe |
| &nbsp;&nbsp;same, maxima 5/10/15/10/10 | 0 | ESTIMABLE | estimable | not recorded | Rotation-made; correctable |
| Script-wise, random piles | 3 | UNDER-EXCH. | under-exch. | not recorded | Correctable if the piles were alike |
| Script-wise, roll-number blocks | 3 | UNDER-EXCH. | under-exch. | not recorded | Same; the assumption is doubtful |
| Question-wise, no overlap | 4 | NOT-ESTIMABLE | none | not recorded | Safe by design |
| Question-wise, one stack order | 4 | NOT-ESTIMABLE | none | under-exch. | Safe; drift rests on stack order |
| &nbsp;&nbsp;alternate stacks reversed | 4 | NOT-ESTIMABLE | none | estimable | Safe; drift measurable |
| Mixed across assessments | 2 | PARTIAL (2+1+1) | estimable | not recorded | Correctable |
| Single marker | 0 | NOT-ESTIMABLE | none | not recorded | No grader facet |
| Two disconnected islands | 4 | PARTIAL (2+2) | not estimable | not recorded | Harmful; unmeasurable |
| Islands + one bridging mark | 3 | UNDER-EXCH. | under-exch. | not recorded | Correctable if the piles were alike |
