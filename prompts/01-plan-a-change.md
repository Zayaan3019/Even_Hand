---
id: plan-a-change
version: 2
used for: the 10 Oct rework (simpler estimator, end-to-end CLI, benchmark)
model and settings: Claude Code (Opus); planning, default temperature
acceptance check: the plan names one module per step and one test per step
---

<role>You are a senior Java engineer reviewing a student project for IIT Madras before a live viva.</role>

<context>
Even Hand audits TA marking in IITM courses: ingest -> audit -> estimate -> outcome -> report.
The approved proposal and design document must stay recognisable; the stakeholder is a Civil
Engineering professor. Modules: {{module table from README}}.
Current state: {{what passes, what is empty}}.
</context>

<task>Propose the smallest set of changes that makes the core workflow run end to end on a
realistic input, in an order where every step leaves `./mvnw verify` green.</task>

<constraints>
- Java 21, Maven, no new third-party runtime dependencies (the arithmetic must be ours).
- Every number the instructor sees is in marks, with an interval.
- The stack, modules and estimator may all change; prefer what can be explained at a whiteboard
  over what is statistically fashionable.
- Do not change the stakeholder, the problem statement, or the privacy invariants.
- If something in the design should be cut, name the design-document cut line that allows it.
</constraints>

<output>A numbered plan: step, module, files touched, the test that proves the step. Then the
three biggest risks, each with a fallback. No code yet.</output>

Restating the task: a plan, not code; the smallest change set; a green build after every step.

## Changelog
- v2 (10 Oct): one constraint changed (a minimal pair with v1). v1 asked us to keep the design's
  stack; our follow-up was "do not hesitate to change the tech stack, architecture and pipeline",
  so v2 lets them change, on condition that every change can be explained at a whiteboard and that
  any cut names the design document's cut line.
- v1 (10 Oct): first version. Our original request (one paragraph: "re-design this entire project
  ... to impress the professors") needed three clarifying questions back (scope, data, deadline).
  Each answer is now a line in `<context>` or `<constraints>`; v1 also said "keep the stack".
