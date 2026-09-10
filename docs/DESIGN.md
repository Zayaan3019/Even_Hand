# Even Hand — Design Document

**A Grading-Consistency Audit for IIT Madras Courses**
Mohamed Zayaan S (CE23B092) · Pruthviraj Milind Chougale (CE23B104) — Team *God's Eye*
11 September 2026

> This is the repository-readable mirror of `DESIGN.tex`, which is the submitted PDF source.
> Where the two differ, the `.tex` is authoritative.

---

## 1. What changed since the proposal, and why

The proposal was approved without scope changes, so this document revises it on facts learned
since 1 September rather than on requested cuts. Three matter.

**(a) The Institute prescribes no rule for turning marks into grades.** We read the official
*B.Tech Ordinances and Regulations* (2015 batch) from `iitm.ac.in`. **R.21.1** fixes the grade
set and points; **R.19.2** states markings are on an *absolute* basis; **R.22.1** and
**R.4.4(d)** place the awarding of letters with the Class Committee meeting held within seven
days of the last end-semester examination. **No mean, no standard deviation, no fixed top share,
and no cut-off rule appear anywhere in the document** — the only "cut-off"/"percentile" text
concerns JEE admission eligibility. The familiar relative bands are departmental convention, and
published accounts of them disagree with one another (B-at-mean vs C-at-mean; S at +2σ vs +3σ;
top 5% vs top 10%).

Our proposal described those bands as institute policy. That was wrong, and correcting it
strengthens the design: **the cut-off rule cannot be compiled in, because the Institute delegates
it per course.** What was a convenience ("the rule is pluggable because instructors use all
four") is now a requirement with a citation behind it.

**(b) Grade points are not evenly spaced, so harm is not uniform across boundaries.** R.21.1
gives S=10, A=9, B=8, C=7, D=6, **E=4**, U=0 — there is no grade point 5. A script misplaced
across B/C costs one grade point; across E/U it costs four, and under **R.25.1** a U in a core
course must be repeated.

So the shortlist is ranked by **expected grade-point loss**, `P(grade changes) × Δ points`, not
by probability alone. This also splits the proposal's prediction into two testable halves:
*transfers* should concentrate at B/C and A/B where student density peaks, while *damage*
concentrates at D/E and E/U where the point gaps are widest.

**(c) Computing rank rather than connectivity showed that the two marking arrangements fail
differently, and only one is recoverable.** This is the main technical finding of the design
phase — see §2.4.

---

## 2. Architecture

### 2.1 The pipeline is a compiler

The platform's first output is not an estimate but a verdict on what the data can support. That
is a static analysis, and the system is built as one: it rejects ill-formed input before the
expensive stage, names the construct at fault, and proposes a rewrite.

| Compiler stage | Even Hand |
| --- | --- |
| Lexer / parser | `eh-ingest` — CSV to typed responses, diagnostics carry row numbers |
| Symbol table / IR | `eh-core` — the marking design as an incidence structure |
| **Semantic analysis** | **`eh-design` — the identifiability audit; refuses ill-formed designs** |
| Fix-it hint | `eh-linking` — the smallest repair that would make it well-formed |
| Code generation | `eh-estimate` → `eh-outcome` — runs only on a design that type-checked |
| Runtime | `eh-app` — Spring Boot, Thymeleaf, SQLite, bound to `127.0.0.1` |

### 2.2 The interface between the two halves

The halves meet at one record, `FittedModel`, carrying the three parameter vectors, their
standard errors, the anchoring used, the parameter covariance and the audit's verdict. Neither
half reaches around it.

The estimator's entry point takes an `AnchoredDesign`, which has a private constructor and one
factory:

```java
AnchoredDesign.of(MarkingDesign design, AuditVerdict verdict, Anchoring anchoring)
```

It refuses a verdict of `NotEstimable`, and refuses any verdict whose certificate does not carry
*this* design's fingerprint. So an unaudited fit is not something we remember not to write — it
is something that **cannot be written**, and a clean verdict earned on one course cannot be
presented as evidence about another.

The check sits at the seam rather than inside the audit deliberately. The two halves are owned by
different people and examined separately, so the audit emits a **certificate** (rank, excess null
dimension, null-space basis, design fingerprint) and the estimator re-verifies it. The contract is
proof-carrying: neither side has to trust the other's boolean.

### 2.3 Nothing about a course is compile-time

Courses differ in class size, team size, score type and cut-off rule — and §1(a) shows the rule is
not even institutionally fixed. So every course-shaped fact is:

1. **Inferred** by the profiler — arrangement per assessment, double-marked units, score type and
   categories, whether piles look like roll-number blocks, whether marking order was recorded;
2. **Asked once**, pre-filled with the profiler's guess, on a first-login intake screen;
3. or **defaulted** and flagged as unconfirmed.

Each field of `CourseProfile` carries its `Provenance` (`DETECTED` / `DECLARED` / `DEFAULTED`) and
its evidence, and every report footer states which of its inputs were measured and which were
asserted, so a disputed conclusion traces to the assumption under it. No control flow branches on
the arrangement; the label exists only for the report. Remaining assumptions are listed in
[`ASSUMPTIONS.md`](ASSUMPTIONS.md) with the knob that overrides each.

### 2.4 The identifiability audit, and what it found

With linear predictor `η_nij = θ_n − δ_i − γ_j`, adding a constant to every ability and every
difficulty changes nothing, and so does adding one to every ability and every severity. That
two-dimensional indeterminacy is in every design and is what anchoring removes; dimensions beyond
it are real confounds.

Rather than measure the null space and interpret it, we ask the instructor's own question once per
pair of assistants — *is the difference between these two severities estimable?* — which is the
question of whether the vector expressing it lies in the row space of the design. Rank is computed
over the integers by fraction-free elimination (`ExactRowSpace`), so the verdict is a fact about
the arrangement rather than a consequence of a floating-point threshold. The same quantities are
computed again from the eigenvalues of the Fisher information and the two must agree on every
fixture.

The incumbent, Facets, uses a graph-joining heuristic after Weeks & Williams (1964,
*Technometrics* 6:319–324) whose manual states that *"there are exotic forms of connectedness
which Facets may falsely report as disconnected."* Computing the rank is what avoids that class of
error. Facets also treats the nested case by asking the user to **declare** their way out of it
(group-anchoring under assumed random equivalence, anchoring items to equal difficulty, virtual
equating); we **measure** whether the data escapes it, and if not, compute what would.

#### The finding

**Script-wise and question-wise marking are equally rank-deficient — both have excess null
dimension `J−1` for `J` assistants, so neither identifies a single severity difference — but they
fail for different reasons and only one is recoverable.**

- **Question-wise:** severity is confounded with **question difficulty**. No assumption about
  students touches it, because no student was marked on that question by anyone else.
- **Script-wise:** severity is absorbed into the **abilities of the students in that pile**. This
  *is* recoverable, by assuming the piles were alike — precisely what random allocation buys.

The audit separates them by re-asking the same estimability question against a design whose
student columns are collapsed into one intercept (`DesignMatrix.withStudentsCollapsed()`).
Collapsing the students **is** the exchangeability assumption. Hence four verdicts, not two:
`ESTIMABLE`, `ESTIMABLE_UNDER_EXCHANGEABILITY` (reported as resting on a named assumption rather
than quietly), `PARTIALLY_ESTIMABLE`, `NOT_ESTIMABLE`.

Two consequences:

- **A limitation, stated rather than hidden.** A random split and a fixed roll-number split are
  *the same design of the same rank*. The audit cannot distinguish them and does not pretend to —
  it reports whether each pile is a contiguous block of roll numbers as evidence for the reader.
- **An actionable finding.** **Question-wise marking in which the assigned marker rotates across
  blocks of roll numbers is fully identified with no double-marking at all** (Appendix A, row 2).
  Nobody marks an extra script. The linking design therefore proposes rotation *before* it proposes
  second marking, because an instructor told to reorder work they were doing anyway will comply at
  a different rate from one told to add work.

**The audit reads no marks.** It is a function of the marking arrangement alone, so an instructor
can be told what his data can support before he shares any of it. This removes data access from
the critical path (§5, R1) and is the artefact we take to the stakeholder in week one.

### 2.5 Estimation, uncertainty and outcome

Many-facet Rasch by joint maximum likelihood (PROX start, alternating Newton updates, re-centring
to the chosen anchoring), with partial-credit thresholds where scores are not binary and a
documented category-collapsing rule. Standard errors from the full observed information,
generalised-inverted on the identified subspace. Facets documents its JMLE bias correction as a
*manual* procedure — simulate, refit, regress, divide by the slope, multiply standard errors by
its square root — and because we needed the synthetic generator anyway, we automate it.

Severity is estimated per (assistant, marking session); whether sessions may be pooled is tested by
likelihood ratio, not assumed. Item diagnostics report infit/outfit plus a point-measure
correlation labelled honestly as a diagnostic *outside* the Rasch model, and **erratic** markers
are separated from **harsh** ones, because conflating them is what would make an output unsafe to
show an assistant.

**One uncertainty engine drives every interval.** It draws from the parameter covariance on the
identified subspace and pushes each draw through the whole deterministic downstream — re-weighting
the composite (R.19.1 makes a course grade a weighted composite whose parts may have been marked
under different arrangements), recomputing boundaries, assigning grades, counting transfers. Grade
transfers, the share of class spread attributable to assistants rather than students, and the
shortlist's `P(grade changes)` therefore come from one component that is tested once. The
permutation test is separate and does refit: a design-preserving relabelling gives the null
distribution of severity spread and the minimum effect detectable at 80% power.

`GradingRule` is a sealed interface with sigma-band, top-fraction, absolute and instructor-band
implementations and an explicit `sBandPolicy`, because §1(a) shows no canonical convention exists.
Boundaries are recomputed on corrected marks, never held fixed. The shortlist is capped, ranked by
expected grade-point loss, carries the **R.24.1** revision deadline on every row (a student has
three weeks from the start of the following semester), and **refuses to emit** when the intervals
cannot support it.

### 2.6 Privacy and reproducibility

Identifiers are HMAC-SHA-256 hashed with a per-run salt at ingest; only the digest reaches SQLite.
The salt lives in memory and in the instructor's key file, which the application never reads back.
Every run writes a manifest — input SHA-256, salt fingerprint (not the salt), seed, git commit,
configuration, verdict — that reproduces the report byte-for-byte. No output ranks named
individuals and every figure carries its interval.

---

## 3. Module split and ownership

Nine Maven modules; dependencies flow strictly downward. **`eh-estimate`'s POM does not offer
Spring, JPA or SQLite**, so "the estimator has tests that run without a database" is a property of
the build graph rather than of our discipline. ArchUnit asserts the same rules a second way, and
`.github/CODEOWNERS` maps each module to its owner's GitHub handle — the course guide's §10 "named
module ownership", machine-enforced.

| Module | Responsibility | Owner |
| --- | --- | --- |
| `eh-core` | Seam records, sealed verdicts, `CourseProfile`; zero dependencies | shared |
| `eh-numerics` | Exact rational elimination, Jacobi eigen, Cholesky, seeded RNG | Zayaan |
| `eh-ingest` | CSV, column mapping, HMAC pseudonymisation | Zayaan |
| `eh-design` | Profiler, identifiability audit, design-only precision forecast | Zayaan |
| `eh-estimate` | MFRM/PCM by JMLE, drift, permutation and uncertainty engines | Zayaan |
| `eh-linking` | Smallest repair reaching a stated precision | Pruthviraj |
| `eh-outcome` | Grading rules, composites, boundaries, transfers, shortlist | Pruthviraj |
| `eh-report` | Marks-unit narration, Class Committee sheet, run manifest | Pruthviraj |
| `eh-app` | Spring Boot, Thymeleaf, JPA/SQLite, CLI | Pruthviraj |

---

## 4. Test plan

At least one named test per module. All test dependencies are **test-scope only**, so no
third-party numerics ever reach the production classpath and the estimation code stays examinable.

Categories: unit (JUnit 5 + AssertJ), property-based (jqwik), **statistical calibration** (seeded),
scenario matrix, golden snapshots, integration (real SQLite in a temp dir), **privacy**,
performance guard, negative control.

| Module | Tests |
| --- | --- |
| `eh-core` | **passing** — a certificate is bound to its design and cannot be moved to another; `AnchoredDesign` refuses a `NotEstimable` verdict and carries the refusal; a verdict can neither over- nor under-claim its certificate; fingerprint is order-independent but content-sensitive; `MarkingDesign` holds no score |
| `eh-numerics` | **passing** — rank vs hand-computed matrices; duplicate rows never inflate rank; membership is exact where a tolerance would have to guess; null-space vectors are annihilated by every row; exact rank agrees with the eigenvalue count; pseudo-inverse leaves the singular direction alone; nullity is scale-invariant |
| `eh-ingest` | **passing** — **no real roll number or assistant name survives ingest** (the whole downstream output is searched for the identifiers that went in, and only the instructor's key file contains them); a second mark on the same response is recorded as a link, not a duplicate; malformed rows are reported with line numbers and do not abort the file; columns located by header name in any order or spelling; a fresh salt breaks linkage between runs |
| `eh-design` | **passing** — the scenario matrix (Appendix A), asserted verdict per arrangement *including the refusals*; one bridging mark measurably improves the verdict; verdict depends only on the arrangement. *planned* — profiler recovers arrangement and score type from data it was never told; provenance override flips a field to `DECLARED` |
| `eh-estimate` | *planned* — parameter recovery from known parameters; **interval calibration** (95% intervals cover ≈95% over seeded replications); monotone likelihood per iteration; PCM reduces to dichotomous at m=1; anchoring-invariance; automated bias correction recovers a known slope; **negative control** — auto-scored fixture yields severity inside the permutation null |
| `eh-linking` | *planned* — connectivity repair is provably minimal (`c−1` links); achieved standard error meets target; rotation preferred to second marking where both suffice |
| `eh-outcome` | *planned* — grading rules vs hand-computed cohorts; fixed-share exact-exchange invariant (#promoted = #displaced) as a property test; weighted composite under R.19.1; grade-point-loss ranking including the E/U boundary; ties and rounding |
| `eh-report` | *planned* — marks-unit round trip; **no-leakage** (rendered output contains no name from the key file); R.24.1 deadline correct; golden snapshots |
| `eh-app` | *planned* — context loads; MockMvc smoke on every page; end-to-end run over HTTP; `127.0.0.1` binding asserted; ArchUnit module-boundary rules; performance guard at N=150 |

---

## 5. Revised milestone plan

Revisions against proposal §8, with reasons:

| # | Revision | Reason |
| --- | --- | --- |
| **R1** | The audit moves ahead of the marks | It is a function of the design, not the scores. Week 1 delivers a verdict and precision forecast with no marks in hand, removing the largest dependency from the critical path. |
| **R2** | Build order re-prioritised, design **not** narrowed | The first course is question-wise, so the refusal path, bridge analysis and linking design move to weeks 1–2. All other arrangements stay green in the scenario matrix. |
| **R3** | Precision, not identifiability, is the binding risk | At 60–150 students and 3–5 assistants. The design-only SE forecast becomes a deliverable and gates whether a shortlist is produced. Mid-demo criterion becomes "severity estimated *or* correctly refused with the repair specified." |
| **R4** | Severity estimated per marking session | Required by the mixed arrangement used for assignments; pooling tested by likelihood ratio. |
| **R5** | *New:* the cut-off rule is unregulated configuration, not institute policy | §1(a). Adds the intake flow and `sBandPolicy`, plus a week-1 question to the stakeholder. |
| **R6** | *New:* composite multi-assessment scoring in scope from the start | R.19.1. |
| **R7** | *New:* outputs aim at the Class Committee meeting and the R.24.1 window | One-page committee sheet, 60-second runtime budget, revision deadline on every shortlist row, linking design retargeted to the post-Quiz-II meeting for this course's own end-sem (R.4.4(a)). |
| **R8** | The cut line gets dates, not just an order | Question diagnostics cut if the W3 gate misses (2 Oct), drift if W5 misses (16 Oct), linking design if W6 misses (23 Oct). Audit, estimator, propagation and blind re-grade are never cut. Pre-registration committed to git in W4. |

| Week | Dates | Deliverable / gate | Lead |
| --- | --- | --- | --- |
| W0 | 10–11 Sep | **done** — repo, seam types, audit, generator, scenario matrix green | both |
| W1 | 12–18 Sep | Allocation table obtained; real verdict + precision forecast to stakeholder; his cut-off convention recorded | Zayaan |
| W2 | 19–25 Sep | Profiler; dichotomous JMLE with standard errors; recovery test | Zayaan |
| W3 | 26 Sep–2 Oct | Partial credit; observed-information SEs; marks conversion; uncertainty engine | Zayaan |
| W4 | 3–8 Oct | Permutation test; grading rules + composite; views 1–4; pre-registration committed | both |
| — | **9 Oct** | **Mid-demo** — core workflow end to end on real input, plus an honest gap list | both |
| W5 | 10–16 Oct | Transfers; variance decomposition; shortlist by grade-point loss; refusal path | Pruthviraj |
| W6 | 17–23 Oct | Drift; allocation regime; linking optimiser; negative control | both |
| W7 | 24–30 Oct | Second real course end to end; blind re-grade issued; handoff pack | Pruthviraj |
| W8 | 31 Oct–5 Nov | Re-grade results; hardening; stakeholder dry-run; cross-module viva prep | both |
| — | **6 Nov** | **Final submission + viva** | both |

---

## 6. Risks and plan B

1. **The first course is question-wise, so severity may be inseparable from question difficulty.**
   Now the expected case, not a tail risk. *Plan B is already the main line*: the audit reports it
   as a verdict, names the assistants affected, and specifies the repair. The double-marked scripts
   the stakeholder confirmed exist are the live mitigation, and week 1 establishes on the
   allocation table alone whether they are enough. If they are not, the first course's audit is a
   result in its own right and the case study moves to a course whose arrangement supports
   estimation.
2. **Precision, not identifiability, is the likeliest failure.** With 3–5 assistants and a thin
   overlap, severities may be identified but with intervals too wide to act on. We report the
   intervals and **refuse to produce a shortlist the estimates cannot support** — implemented as a
   type, not a promise.
3. **The marks have not arrived.** Mitigated structurally by R1: the audit, the precision forecast
   and the linking design all run on an allocation table with no scores in it, so a delay costs us
   the estimate and not the project.
4. **The cut-off convention we assumed may not be his.** §1(a) shows the Institute prescribes none
   and public accounts disagree. Every policy ships behind configuration and is labelled
   unconfirmed until he confirms it; only the default is at stake.
5. **The permutation test comes back null.** A legitimate outcome, treated as one. We report the
   null, the effect size we can rule out, and the sample that would have been needed. We will not
   go looking for a different course until we find a positive result, and we say so in the report.
6. **Severity estimates could damage an assistant's standing.** Pseudonymised at ingest with only
   the instructor holding the key; no output ranks named individuals; every estimate carries its
   interval; erratic markers reported separately from harsh ones; the framing throughout is
   calibration of a marking session rather than judgement of a person.
7. **He declines the blind re-grade.** Negotiate down to ten flagged and ten control scripts,
   agreed in advance. If he declines entirely we report the missing experiment as a gap rather than
   substituting a weaker proxy.

---

## Appendix A — scenario matrix, as it runs today

Output of `./mvnw -pl eh-design test` and `AuditReport.main`. Every row is an arrangement whose
right answer was worked out on paper before the audit was written. `J` is the number of
assistants; **Exc.** is the null dimension beyond the two that anchoring removes. **No marks are
involved in any row.**

| Arrangement | J | Exc. | Verdict | Why it matters |
| --- | --- | --- | --- | --- |
| Question-wise + double-marking | 5 | 0 | `ESTIMABLE` | The stakeholder's course. The overlap set is what rescues it. |
| Question-wise, marker rotated | 5 | 0 | `ESTIMABLE` | Identified at zero extra marking cost. |
| Script-wise, random piles | 4 | 3 | `UNDER-EXCH.` | Rests on an assumption, and says so. |
| Script-wise, roll-number blocks | 4 | 3 | `UNDER-EXCH.` | Same design, same rank as random: the audit cannot tell them apart, and reports the pile shape instead of guessing. |
| Question-wise, no overlap | 5 | 4 | `NOT-ESTIMABLE` | Correctly refused. Excess is `J−1`: not one severity difference is estimable. |
| Mixed across assessments | 4 | 2 | `PARTIAL (2 of 4)` | Two assistants reachable, two not. |
| Single marker | 1 | 0 | `NOT-ESTIMABLE` | Rank is fine; there is simply no grader facet. |
| Two disconnected islands | 4 | 4 | `PARTIAL (2 of 4)` | Names which assistants it can reach and which it cannot. |
| Islands + one bridging mark | 4 | 3 | `UNDER-EXCH.` | One second mark buys back a dimension and connects all four. |

**State of the repository at submission.** `eh-core`, `eh-numerics`, `eh-ingest` and `eh-design` are implemented
and tested (36 tests passing); the synthetic generator was written *before* the estimator, as
promised. `eh-estimate`, `eh-linking`, `eh-outcome`, `eh-report` and
`eh-app` are declared modules with their interfaces fixed and no implementation yet; their tests
are listed above as *planned* rather than passing.
