# Even Hand

**A grading-consistency audit for IIT Madras courses.**
CS5013 project · Team *God's Eye* · Mohamed Zayaan S (CE23B092), Pruthviraj Milind Chougale (CE23B104)
<https://github.com/Zayaan3019/Even_Hand>

Large courses here are marked by teams of assistants who divide the scripts and mark
independently. That can put a component into a student's score that has nothing to do with the
student: part of what you are awarded depends on whose pile your script landed in. Under a
relative grading rule the misplacement is zero-sum — a leniently marked student who rises into a
band displaces a harshly marked one out of it.

This tool measures that, for an arbitrary course, and refuses to when the marking arrangement
cannot support it.

## The two things worth knowing first

**The audit needs no marks.** Whether an assistant's severity is estimable, and whether it could
have harmed anyone, are properties of *who marked what*, not of the scores. So an instructor can be
told what his marking arrangement can support before he shares a single mark.

**Severity harms a student only through differential exposure.** If every student's marks of record
came from the same assistants in the same proportions — one marker per question, say — a harsh
assistant is indistinguishable from a harder question and moves nobody relative to anybody. Such a
course is safe by design even though its severities cannot be estimated, and the audit says so
rather than raising an error.

```
./mvnw -q -pl eh-core,eh-numerics,eh-design -am install -DskipTests
java -cp "eh-design/target/classes;eh-core/target/classes;eh-numerics/target/classes" \
  in.ac.iitm.evenhand.design.AuditReport
```

```
ARRANGEMENT                         J EXC  SEVERITY        EXPOSURE       DRIFT
-------------------------------------------------------------------------------------
Question-wise + double-marking      5   0  ESTIMABLE       none           not recorded
Question-wise, marker rotated       5   0  ESTIMABLE       none           not recorded
  same, maxima 5/10/15/10/10        5   0  ESTIMABLE       estimable      not recorded
Script-wise, random piles           4   3  UNDER-EXCH.     under-exch.    not recorded
Script-wise, roll-number blocks     4   3  UNDER-EXCH.     under-exch.    not recorded
Question-wise, no overlap           5   4  NOT-ESTIMABLE   none           not recorded
Question-wise, one stack order      5   4  NOT-ESTIMABLE   none           under-exch.
  alternate stacks reversed         5   4  NOT-ESTIMABLE   none           estimable
Mixed across assessments            4   2  PARTIAL (2+1+1) estimable      not recorded
Single marker                       1   0  NOT-ESTIMABLE   none           not recorded
Two disconnected islands            4   4  PARTIAL (2+2)   not estimable  not recorded
Islands + one bridging mark         4   3  UNDER-EXCH.     under-exch.    not recorded
```

Each finding is shaped like a compiler diagnostic: it names the assistant and the question, says
whether it matters, and says what would change it.

```
EH003 note: This assistant alone marked Q1, so their severity cannot be separated from Q1's
      difficulty. It also cannot have moved anyone: every student's Q1 was marked by this
      assistant. [Assistant 1, Q1]
      fix: Only if you want per-assistant feedback: have a second assistant mark ten or so Q1 scripts.
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

`eh-core`, `eh-numerics`, `eh-ingest` and `eh-design` are implemented and tested (104 tests, one
of them a property checked on 150 random allocations). `eh-estimate`, `eh-linking`, `eh-outcome`,
`eh-report` and `eh-app` are declared modules whose signatures are fixed in the design document and
which have no implementation yet. The design document's test plan marks each test *passing* or
*planned* accordingly.

## Privacy

Runs locally, bound to `127.0.0.1`, with no external API. Identifiers are HMAC-hashed at ingest
and numbered in keyed order, so a pseudonym says nothing about anyone's place in a list. The only
table mapping pseudonyms back to people is the instructor's key file, sealed with AES-256-GCM under
a passphrase before it is written. Marks and identities never leave the machine they are loaded on.
See [`docs/data-sharing-note.md`](docs/data-sharing-note.md).
