# Even Hand

**A grading-consistency audit for IIT Madras courses.**
CS5013 project · Team *God's Eye* · Mohamed Zayaan S (CE23B092), Pruthviraj Milind Chougale (CE23B104)
Stakeholder: Prof. Aslam Kunhi Mohamed, Department of Civil Engineering · <https://github.com/Zayaan3019/Even_Hand>

Large courses here are marked by teams of TAs who split the scripts. Part of what a student is
awarded then depends on whose pile the script landed in. Under relative grading (B at the class
mean, A one SD above, top 10% S) that is zero-sum: a leniently marked student who rises into a
band pushes a harshly marked one out of it.

Even Hand answers four questions for an instructor, in this order, from the marks file they
already have:

1. **Can this marking arrangement be checked at all, and could it have harmed anyone?** Answered
   from *who marked what* alone, before any mark is read.
2. **How differently did the TAs mark?** In marks, with a 95% interval for every TA.
3. **What did it do to grades?** The course regraded under its own rule, as if every script had
   been marked by an average TA.
4. **Which scripts should be looked at again?** At most 20, most consequential first. The tool
   never changes a mark; a grade changes only by a human re-mark.

## Run it (Java 21+, nothing else to install)

**For the instructor:** double-click `even-hand.cmd` (Windows) or run `./even-hand.sh`. Your browser
opens a page on this computer only (`http://127.0.0.1:8642`). Pick the marks file, type the weights,
and press *Analyse*.

**The Simulation Lab** (`http://127.0.0.1:8642/lab`): pick a course shape, set how differently the
TAs mark, and run. The real pipeline analyses a simulated course and shows the hidden truth beside
every estimate: who marked what, each TA's true vs. estimated severity, the grades that really moved,
and the re-check list against today's practice.

**From the terminal:**

```
./mvnw -q package -DskipTests                     # builds eh-app/target/even-hand.jar
java -jar eh-app/target/even-hand.jar run samples/ce-core-7.csv --weights "Quiz 1=25,Quiz 2=25,End-sem=50"
```

```
AUDIT     Every assistant can be placed on one scale, from the marking alone.
ESTIMATE  severity, percentage points of a question's maximum (+ = harsh):
          Assistant 1   TA02         +4.3  [+2.7, +5.9]  454 marks
          Assistant 2   TA01         +1.8  [+0.1, +3.1]  456 marks
          Assistant 3   TA03         -6.1  [-7.5, -4.6]  368 marks
          no-effect test p = 0.010
OUTCOME   1.6% of the spread in totals came from who marked; 10 grade(s) would change (90% range 8-14)
RE-CHECK  13 script(s), most consequential first:
          CE23B154   Student 25    B -> C  100%  total 62.5 -> 60.2
          ...
```

(The planted truth for this sample, `samples/ce-core-7.truth.csv`: TA02 +4.1, TA01 +2.1, TA03 -6.3.
Pseudonyms such as "Assistant 1" change from run to run, because the key is drawn fresh.)

`report.html` is for the instructor only. `committee.html` is for the Class Committee and carries
aggregates only. Roll numbers appear once, in the terminal, and are never written to disk.

**Input:** one row per marked answer, columns found by header name:
`roll_no, assessment, question, max_marks, marks, ta[, role]`. Half marks are fine. A `role` of
`second` marks a checking mark that did not count towards the grade. See `samples/`.

**Options:** `--rule relative` (default: B at the mean, ±1/2/3 SD, top 10% S), `relative-sigma`
(no fixed S share) or `absolute:S=90,A=80,B=70,C=60,D=50,E=40`; `--name`, `--out`, and
`--seal-key-with-env VAR` to keep the pseudonym key, encrypted under the passphrase in `VAR`.

## How it works: five stages, one module each

```
marks.csv ─► [1 INGEST] ─► [2 AUDIT] ─┬─► [3 ESTIMATE] ─► [4 OUTCOME] ─► [5 REPORT] ─► report.html
            pseudonymise   who marked │   TA severity     regrade under    instructor     committee.html
            validate       what: can  │   in marks,       the course's     + committee
                           it be      │   bootstrap,      rule; re-check
                           measured?  │   no-effect test  list
                                      └─► refused: says why and names the repair
```

| Module | Stage | Key class | What it does |
| --- | --- | --- | --- |
| `eh-ingest` | 1 | `CsvIngest` | Reads the CSV and replaces roll numbers and TA names with keyed pseudonyms (HMAC-SHA-256) before anything else sees them. Bad rows are reported with line numbers. |
| `eh-design` | 2 | `IdentifiabilityAudit` | Decides from the allocation alone whether TA severity is measurable (exact integer rank) and whether it could have moved anyone. Also holds `CourseSimulator`, the dataset generator. |
| `eh-estimate` | 3 | `SeverityEstimator` | Fits `mark% = mean + ability + easiness − severity` by backfitting (repeated weighted averages). Gives intervals by resampling students and a p-value by re-simulating the course with identical TAs. |
| `eh-outcome` | 4 | `Outcome`, `GradingRule` | Grades as marked vs. corrected under the course's rule, counts letters that change, builds the re-check list behind a pre-registered gate. |
| `eh-report` | 5 | `HtmlReport` | Self-contained HTML for the instructor; an aggregate-only sheet for the Class Committee. |
| `eh-app` | — | `Pipeline`, `Main`, `LocalServer`, `SimulationLab`, `Benchmark` | `Pipeline.run` is the five stages in order; the CLI, the browser page (JDK HTTP server on 127.0.0.1, no framework), the lab and the benchmark all call it. |
| `eh-core`, `eh-numerics` | — | `FittedModel`, `ExactRowSpace` | Shared types (`FittedModel` is the seam between measuring and grading) and exact arithmetic. |

Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Where to change what:
[`docs/CODE_MAP.md`](docs/CODE_MAP.md).

## The dataset: synthetic IITM courses with known answers

Real marks are private, so the dataset is generated, and that is what makes it useful: every
simulated script is marked twice with the same luck, once by its real TA and once by a perfectly
average one. So for every student we know the grade they *should* have got, which no real course
can tell us.

The generator (`CourseSimulator`) is built around how marking is actually divided here. It
covers seven course shapes: a CE core course like our stakeholder's, a 400-student first-year
core, a question-wise elective, a rotated PG course, lab batches by department, tutorial groups,
and two slot sections. It uses IITM roll numbers (`CE23B092`), quiz/end-sem weights, half marks,
absentees and double-marked samples. Marks come from a logistic curve with reading noise and
rounding, *not* from the model the estimator fits, so the test is honest.

```
java -jar eh-app/target/even-hand.jar simulate ce-core 7 samples/      # one course + its answers
java -jar eh-app/target/even-hand.jar benchmark                        # train, then test (~2 min)
```

**Results on 112 held-out courses** ([`docs/BENCHMARK.md`](docs/BENCHMARK.md)):

- **TA severity error:** about half a percentage point where the marking links the TAs, with 88–95% interval coverage.
- **False alarms:** 4 of 24 courses with identical TAs, and 3 of those 4 are the department-batch trap, whose list the tool refuses anyway.
- **Re-check list:** 80% of flagged scripts really had their grade moved by who marked them. Re-checking the same number of scripts *nearest a grade boundary*, which is what moderation does today, finds 46%.

The two estimator constants were trained on a separate set of 168 courses (144 used, see the
benchmark). The re-check gate was fixed in the design document before any data existed.

## Build and test

```
./mvnw verify               # all modules: 152 tests, JaCoCo coverage in */target/site/jacoco
./mvnw -Pmutation verify -pl eh-outcome   # PIT mutation testing of the grading arithmetic
java -jar eh-app/target/even-hand.jar scenarios        # the audit's verdict on twelve arrangements
```

## Privacy

Runs locally. The browser page is served by the JDK's own HTTP server bound to 127.0.0.1, and an architecture test asserts that nothing else touches the network and nothing is a network client. Identifiers become keyed
pseudonyms at ingest, numbered in digest order so a label reveals nothing about the roll list. The
only mapping back is the key file, sealed with AES-256-GCM under the instructor's passphrase. Real
marks never enter the repository: `data/` and `*.csv` are git-ignored except the synthetic
`samples/`. See [`docs/data-sharing-note.md`](docs/data-sharing-note.md).

## Documents

| Path | What |
| --- | --- |
| [`docs/DESIGN.md`](docs/DESIGN.md) | The design document as submitted (11 Sep), with a revision note |
| [`docs/Even_Hand_Architecture.pdf`](docs/Even_Hand_Architecture.pdf) | One-page architecture and data-flow diagram (source: `docs/diagrams/architecture.html`) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The system as built: stages, data flow, the seam, the decisions |
| [`docs/CODE_MAP.md`](docs/CODE_MAP.md) | Where to make a change, and the test that proves it |
| [`docs/PRESENTATION.md`](docs/PRESENTATION.md) | The 2-minute overview and likely questions |
| [`docs/submission/`](docs/submission/) | Pre-evaluation *Principles & Practices* document (PDF and its HTML source) |
| [`docs/BENCHMARK.md`](docs/BENCHMARK.md) | Dataset, training and held-out test results |
| [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) | Every assumption about a course, and the override for each |
| [`prompts/`](prompts/) | The prompts we engineer with, versioned like code |
| [`docs/log/`](docs/log/) | Weekly contribution logs |
