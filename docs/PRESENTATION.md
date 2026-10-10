# The 2-minute overview

Point at `docs/Even_Hand_Architecture.pdf` while speaking. About 270 words, roughly 2 minutes.
Have the Simulation Lab open in a browser tab (`even-hand.cmd`, then `/lab`) as the backup demo.

---

**The problem (15 s).** Big IITM courses are marked by teams of TAs who split the scripts. Under
relative grading, a lenient TA's students rise into a band and push someone else's out. So part of
a grade depends on whose pile your script landed in. Our stakeholder, Prof. Kunhi Mohamed in Civil,
has no way to know how much.

**The flow (60 s), left to right on the diagram.**
1. *Ingest.* The instructor gives the marks file they already have. Roll numbers and TA names are
   replaced by keyed pseudonyms before anything else sees them.
2. *Audit.* Before reading a single mark, we look only at who marked what, and decide two things:
   can TA severity be measured at all, and could it have moved anyone? If not, we stop and say why.
   For example, one TA per question is "safe by design".
3. *Estimate.* Each mark is mean plus student ability plus question easiness minus TA severity, in
   percentage points, fitted by repeated weighted averages. Intervals come from resampling students,
   and a no-effect test asks whether chance alone could produce the differences.
4. *Outcome.* We grade the course twice under its own rule, as marked and as an average TA would
   have marked, and list at most twenty scripts worth a second look.
5. *Report.* One page for the instructor; an aggregate-only sheet for the Class Committee.

**The evidence (35 s), bottom band.** Real marks are private, so we simulate IITM courses where we
know the truth: every script is marked twice, by its TA and by an average TA. We trained two
constants on 168 courses and tested once on 112 unseen ones. 80% of the scripts we flag really had
their grade changed by who marked them, against 46% for today's practice of re-checking scripts near
a boundary. And when the design can't support an answer, the tool refuses instead of guessing.

**Close (10 s).** One jar, runs on the professor's laptop, nothing leaves it, 152 tests.

---

## Likely questions, short answers

- **Why not the Rasch model from the design?** The instructor grades in marks and the acceptance
  criterion asked for marks. On the benchmark, whose data comes from a logistic (Rasch-like) curve,
  the simpler model is within half a percentage point. And every line can be explained.
- **Why is the dataset synthetic?** Real marks cannot leave the stakeholder's machine, and real
  marks never tell you the right answer. Simulation is the only way to measure accuracy. The first
  real allocation table is the next step, and the audit needs no marks.
- **What does it get wrong?** If TAs mark whole departments (lab batches), severity and department
  cannot be told apart. The benchmark shows estimates about 4 points off, so the tool refuses the list
  and names the repair: double-mark a few scripts across piles.
- **Can it change a grade?** No. It recommends scripts to re-check; only a human re-mark changes a grade.
