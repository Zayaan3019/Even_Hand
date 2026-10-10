---
id: test-first-module
version: 1
used for: eh-outcome (GradingRule, Composite), eh-estimate (Backfit)
model and settings: Claude Code; code generation
acceptance check: ./mvnw -q -o test -pl <module> passes, and the hand-worked oracle is ours, not the model's
---

<role>You write Java 21 for a grading tool whose outputs reach a Class Committee.</role>

<task>Step 1 only: write five JUnit 5 + AssertJ tests for `GradingRule.grade(CutoffPolicy, double[])`,
three happy paths and two edge cases. Do not write the implementation yet.</task>

<context>
- R.21.1 grade points: S=10, A=9, B=8, C=7, D=6, E=4, U=0 (there is no 5).
- Relative rule: B at the class mean; A at +1 SD; S at +2 SD; C at -1; D at -2; E at -3; below E is U.
- Top-share rule: the top 10% (rounded up) get S whatever the SD line says; students tied at the cut all get it.
- Worked example to use verbatim: totals {90,70,50,50,50,50,50,50,30,10} have mean 50 and population
  SD 20, so the grades are S,A,B,B,B,B,B,B,C,D.
</context>

<constraints>
- Population SD, not sample SD; say so in a comment.
- No new dependencies; no mocking.
- Name each test after the behaviour (`tiesAtTheCutAreAllAdmitted`), with a @DisplayName sentence.
</constraints>

<output>One test file only. After it, list any assumption you made that the context did not state.</output>

Step 2 is a separate prompt: "Now write the smallest `GradingRule` that passes these tests. Keep
the signature. Show only the class."

## Changelog
- v1 (10 Oct): first version. Tests and implementation are two prompts (the course's "ask for the
  test first" habit). The worked cohort is computed by hand, so the oracle never comes from the model.
