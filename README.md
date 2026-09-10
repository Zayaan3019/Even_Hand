# Even Hand

**A grading-consistency audit for IIT Madras courses.**
CS5013 project · Team *God's Eye* · Mohamed Zayaan S (CE23B092), Pruthviraj Milind Chougale (CE23B104)

Large courses here are marked by teams of assistants who divide the scripts and mark
independently. That puts a component into a student's score that has nothing to do with the
student: part of what you are awarded depends on whose pile your script landed in. Under a
relative grading rule the misplacement is zero-sum — a leniently marked student who rises into a
band displaces a harshly marked one out of it.

This tool measures that, for an arbitrary course, and refuses to when the marking arrangement
cannot support it.

## The thing worth knowing first

**The audit needs no marks.** Whether an assistant's severity is estimable at all is a property of
*who marked what*, not of the scores. So an instructor can be told what his marking arrangement
can support before he shares a single mark.

```
./mvnw -q -pl eh-core,eh-numerics,eh-design -am install -DskipTests
java -cp "eh-design/target/classes;eh-core/target/classes;eh-numerics/target/classes" \
  in.ac.iitm.evenhand.design.AuditReport
```

```
ARRANGEMENT                          J EXC  VERDICT
----------------------------------------------------------------------
Question-wise + double-marking       5   0  ESTIMABLE
Question-wise, marker rotated        5   0  ESTIMABLE
Script-wise, random piles            4   3  UNDER-EXCHANGEABILITY
Script-wise, roll-number blocks      4   3  UNDER-EXCHANGEABILITY
Question-wise, no overlap            5   4  NOT-ESTIMABLE
Mixed across assessments             4   2  PARTIAL (2 of 4)
Single marker                        1   0  NOT-ESTIMABLE
Two disconnected islands             4   4  PARTIAL (2 of 4)
Islands + one bridging mark          4   3  UNDER-EXCHANGEABILITY
```

A refusal names the assistant and the question at fault and says what would fix it, the way a
compiler diagnostic does:

```
EH003 error: This assistant marked only Q1, and nobody else marked it. Their severity and
      that question's difficulty are only ever observed added together, so a harsh assistant
      and a hard question cannot be told apart. [Assistant 1, Q1]
      fix: Have a second assistant mark some of the scripts for Q1, or rotate this question's
           marker across blocks of roll numbers next time.
```

## Build

Java 21 (the build pins `maven.compiler.release=21`; a newer local JDK is fine). Maven is not
required — the wrapper is committed.

```
./mvnw verify          # build and test every module
./mvnw -Pstats verify  # also run the seeded recovery and coverage studies
```

## Where things are

| Path | What |
| --- | --- |
| [`docs/DESIGN.tex`](docs/DESIGN.tex) | The design document (submitted source; compiles on Overleaf) |
| [`docs/DESIGN.md`](docs/DESIGN.md) | The same, readable on GitHub |
| [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) | Every course-shape assumption and the knob that overrides it |
| [`docs/data-sharing-note.md`](docs/data-sharing-note.md) | Countersigned before any marks are received |
| [`docs/log/`](docs/log/) | Weekly contribution logs |

One end-to-end pipeline in nine modules: a course enters at `eh-ingest` and leaves as a report
from `eh-report` without leaving the reactor, and `./mvnw verify` builds and tests all of it.
Dependencies flow strictly downward, and `eh-estimate`'s POM does not offer Spring, JPA or SQLite,
so the estimator's tests cannot depend on a database even by accident.

## Status

`eh-core`, `eh-numerics`, `eh-ingest` and `eh-design` are implemented and tested (36 tests).
`eh-estimate`, `eh-linking`, `eh-outcome`, `eh-report` and `eh-app` are declared modules with
their interfaces fixed and no implementation yet. The design document's test plan marks each test
*passing* or *planned* accordingly.

## Privacy

Runs locally, bound to `127.0.0.1`, with no external API. Identifiers are HMAC-hashed at ingest
before anything reaches disk; only the instructor holds the key. Marks and identities never leave
the machine they are loaded on. See [`docs/data-sharing-note.md`](docs/data-sharing-note.md).
