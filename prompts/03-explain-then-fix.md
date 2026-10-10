---
id: explain-then-fix
version: 1
used for: defect 2 in docs/ARCHITECTURE.md (no-effect test false alarms), 10 Oct
model and settings: Claude Code; diagnosis and patch are separate prompts
acceptance check: a regression test that fails before the patch and passes after
---

## Prompt 1: explain and rank (no patch)

<context>
Symptom (benchmark, held-out courses): the no-effect test rejects in 4 of 4 TUTORIAL_GROUPS courses
generated with identical TAs (severity SD = 0). Expected: about 5%.
It behaves correctly on CE_CORE and FIRST_YEAR_CORE (0 of 4 each).
Code: `SeverityEstimator.noEffectTest` and `Backfit.fit`, pasted. Nothing else.
</context>

<task>Explain in plain English what most plausibly produces rejections when no effect exists.
Rank the three most likely root causes, and for each give the one experiment that would confirm
or eliminate it. Do not propose a patch.</task>

## Prompt 2: the smallest patch, after we have checked the diagnosis ourselves

<task>Given root cause #1 is correct (the null worlds keep every student in their real pile, so
the luck of the deal is never simulated), write the smallest patch to `noEffectTest`. Keep the
method signature and the seeding scheme. Show only the diff, then one sentence on why it fixes it.</task>

## Changelog
- v1 (10 Oct): first version, written up from the debugging of defect 2.
