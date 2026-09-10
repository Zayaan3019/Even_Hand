# Weekly contribution log — Week 0 (10–11 Sep 2026)

Per the CS5013 project guide section 10, item 5.

## Mohamed Zayaan S (CE23B092)

Set up the multi-module build and wrote the modules I own that exist so far. `eh-core`: the seam
record, the sealed `AuditVerdict`, the `AnchoredDesign` capability type and `CourseProfile` with
per-field provenance. `eh-numerics`: exact integer row-space elimination and the Jacobi
eigen-decomposition. `eh-ingest`: CSV parsing with HMAC pseudonymisation. `eh-design`: the identifiability audit, the synthetic generator (written
before the estimator, as the proposal promised) and the scenario matrix.

The substantive finding this week was that computing the rank, rather than checking connectivity,
shows script-wise and question-wise marking to be equally rank-deficient but for different
reasons — only the script-wise case is recoverable, and then only under an exchangeability
assumption that has to be stated. That is why the audit returns four verdicts rather than two.
The second finding was that question-wise marking with the marker rotated across roll-number
blocks is fully identified with no double-marking at all, which is cheaper advice than anything
in the proposal.

Also read the B.Tech Ordinances and found that no marks-to-grades formula exists anywhere in
them, which corrects a claim in our proposal and is written up in §1(a) of the design document.

## Pruthviraj Milind Chougale (CE23B104)

*To be completed by Pruthviraj.* Reviewing this week's commits; picking up the outcome and
reporting stages of the pipeline (`eh-linking`, `eh-outcome`, `eh-report`, `eh-app`), whose
interfaces are fixed and whose implementation begins in week 1.

## Gaps, honestly

- `eh-estimate`, `eh-linking`, `eh-outcome`, `eh-report` and `eh-app` are empty modules. Their
  tests appear in the design document's test plan marked *planned*, not *passing*.
- The design document has not been compiled to PDF on this machine (no LaTeX installed); it needs
  one Overleaf pass before submission.
- The audit has not yet been run on the real course's allocation table, because we do not have it.
  That is week 1's first task and needs no marks.
