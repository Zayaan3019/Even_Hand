---
id: edge-cases
version: 1
used for: CsvIngest, Composite, GradingRule, ExposureAudit
model and settings: Claude Code; brainstorming, default temperature
acceptance check: every edge case we accept becomes a named test or a line in docs/ASSUMPTIONS.md
---

<role>You are a test engineer who has marked exams at an Indian university.</role>

<task>List edge cases for `{{class}}` that a real IIT Madras marks export would contain. For each,
give the input, what the code should do, and whether it is an error, a warning or normal.</task>

<context>{{the class's public method and its Javadoc only}}</context>

<constraints>
- Real-world cases first: absentees, half marks, an "answer any four of five" paper, a TA column with
  trailing spaces, a running-total column mistaken for a maximum, two marks for one answer, ties at
  a grade cut, a 400-student class.
- No cases about Java itself (nulls, overflow) unless the input can cause them.
- At most 15, most likely first.
</constraints>

<output>A table: case | input | expected behaviour | severity.</output>

## Changelog
- v1 (10 Oct): first version. "Absentee" is listed first because it broke the exposure audit (defect 1).
