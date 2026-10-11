# Code map: where to change what

For the live code modification. Each row is a change an examiner might plausibly ask for, the file
it belongs in, and the one test that proves it worked.

## Fast loop

```
./mvnw -q -o install -DskipTests                         # build everything (~15 s)
./mvnw -q -o test -pl eh-outcome                          # one module's tests
./mvnw -q -o test -pl eh-outcome -Dtest=GradingRuleTest   # one test class
java -jar eh-app/target/even-hand.jar run samples/ce-core-7.csv --weights "Quiz 1=25,Quiz 2=25,End-sem=50"
```

`-o` (offline) is safe once the first build has downloaded everything.

## Follow one run through the code

`LocalServer.report` or `Main.run` → `Pipeline.run` (five numbered comments, one per stage) → `CsvIngest.read` →
`IdentifiabilityAudit.audit` → `SeverityEstimator.fit` → `Backfit.fit` → `Outcome.assess` →
`GradingRule.grade` → `HtmlReport.instructor`.

## Likely changes

| Asked for | Where | How | Prove it |
| --- | --- | --- | --- |
| Top 15% get S instead of 10% | `eh-core/.../CutoffPolicy.java`, `iitmConventionalRelative()` | change `0.10`; or add a `--top-s` flag in `CourseConfig.parseRule` building a new `CutoffPolicy` with that fraction | `GradingRuleTest.topTenPercentGetS` (update the count) |
| A different relative rule (e.g. A at +1.5 SD) | same method, `bandOffsets` map | edit the offset | `GradingRuleTest.sigmaBands` |
| An absolute fail mark (U below 35 whatever the curve) | `eh-outcome/.../GradingRule.java`, `relative(...)` | after the loop: `if (totals[i] < failMark) out[i] = LetterGrade.U;` with `failMark` from `CutoffPolicy` or a constant | add a case to `GradingRuleTest` |
| Re-check list of 10 instead of 20, or threshold 30% | `eh-outcome/.../Outcome.java`, `Gate.standard()` | `new Gate(0.05, 0.30, 10)` | `OutcomeTest.cappedAndRanked` |
| Stricter significance (1%) | same, first argument of `Gate` | `0.01` | `OutcomeTest.refusesWhenTheNoEffectTestDoesNot` |
| Count grade changes at each boundary (e.g. B↔C) | `Outcome.assess`, the loop comparing `gradesCorrected[i]` with `gradesMarked[i]` | a `Map<String,Integer>` keyed `marked + "->" + corrected`; add it to `Result` | new assertion in `OutcomeTest` |
| Show severity in marks on a 20-mark question | `eh-report/.../HtmlReport.java`, `inMarks` and the table header | divide by 5 instead of 10 | `HtmlReportTest.instructorReportShowsSeverities` |
| A new column in the TA table (e.g. scripts marked) | `HtmlReport.severityTable` | one more `<th>` and one more `%s` in the row format | `HtmlReportTest` |
| Flag TAs harsher than ±5 points | `HtmlReport.severityTable` or `Main.printSummary` | `Math.abs(m.severity(j)) > 5 ? " ⚑" : ""` | `HtmlReportTest` |
| Accept a CSV whose TA column is called "evaluator_name" | `eh-ingest/.../CsvIngest.java`, `ALIASES` | add `"evaluator_name"` to the `grader` list | add a header case to `CsvIngestTest` |
| Export the re-check list as CSV (pseudonyms only) | `eh-app/.../Main.java`, `run` | after writing the HTML, loop over `Outcome.Emitted.rows()` and `Files.writeString(out.resolve("recheck.csv"), ...)`; never write `key.real(...)` | `CliTest.simulateThenRun` |
| More bootstrap draws / a different seed | `eh-estimate/.../Settings.java`, `defaults()` | change `200` / the seed | `SeverityEstimatorTest.reproducible` |
| Stop shrinking TAs | `Settings.defaults()` second argument → `0` | | `SeverityEstimatorTest.shrinkageOnlyPullsTowardsZero` |
| A new course shape in the dataset | `eh-design/.../sim/CourseSimulator.java`, enum `Archetype` | add a constant with its size, TAs, departments and `AssessmentPlan`s | `java -jar ... simulate <name> 1 samples/` |
| A new kind of TA allocation | `CourseSimulator.Allocation`, then `piles(...)` and `marker(...)` | a new enum value and its case in both switches | simulate and run it |
| A new control on the Simulation Lab (e.g. class size) | `eh-app/src/main/resources/lab.html` (the `controls` div and `run()`), `LocalServer` (`/lab/run` parameters), `SimulationLab.run` | read the new parameter and pass it to the simulator | `LocalServerTest.labRun` |
| A new field on the upload page | `eh-app/src/main/resources/upload.html` (step 2 and the `URLSearchParams` in the *Check the marking* handler), `LocalServer.run` (`param(q, ...)`) | | `LocalServerTest.analyseKeepsNamesOutOfTheReports` |
| Accept another way of writing a question's maximum, e.g. `Q1 - 10` | `eh-ingest/.../WideSheet.java`, the `QUESTION` pattern | one more alternative in the regex | a header case in `CsvIngestTest.readsOneRowPerStudent` |
| Treat another cell value as "not answered", e.g. `MP` | `WideSheet.NOT_ANSWERED` | add `"mp"` | `CsvIngestTest.readsOneRowPerStudent` |
| Change the report's opening summary | `eh-report/.../HtmlReport.java`, `bottomLine` | | `HtmlReportTest.bottomLineNamesTheHarshestAssistant` |
| Weights given as fractions (0.25) | nothing: `Composite` normalises any weights to sum to 1 | | `CompositeTest.weighted` |

## Things to be able to say about any line

- **Why percentage points?** The instructor grades in marks; a 4/5 and an 8/10 are both 80.
- **Why weight by the question's maximum?** A 20-mark answer tells you more than a 2-mark one.
- **Why centre severities on zero?** Only differences between TAs are measurable. A uniformly harsh team
  is indistinguishable from a hard paper (ASSUMPTIONS, "collective severity").
- **Why resample students, not marks?** A student's marks are not independent of each other, and
  which students landed in which pile is the main source of uncertainty.
- **Why can the list be refused?** A list from a design that cannot support it would look precise and
  be wrong. Refusal is a value (`Outcome.Refused`) carrying its reason.
