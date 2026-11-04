# What we said would count as passing, before we looked

Committed to git before any result on these datasets was read, so the commit's date is the evidence.
Thresholds were not adjusted afterwards. A failure is reported here as a failure; if it leads to a fix, the
fix is measured again and both results stay on the record.

UNT (`docs/VALIDATION.md`) is not covered: its even-numbered questions were used for tuning and its other
results were already seen. These are the held-out tests.

## Datasets

| Dataset | Why it is here | What it tests |
|---|---|---|
| ASAP short answers (17,207 answers, 2 scores each, CC BY 4.0) | Largest, nothing tuned on it | Scale; a second subject mix |
| ASAP essays (12,978 essays, 2 raters each, CC BY 4.0) | Answers far longer than the model reads | Long answers |
| JorGPT (3,041 answers, 79 real students across 50 questions, CC BY 4.0) | Real students with whole scripts, marked in 2025-26 | The student part of the model, on real students |
| JorGPT in its original Spanish | A language the text model was not built for | Whether it stays honest when the text helps less |
| CityU Operating Systems (240 answers, each marked by 3 real TAs) | Real TAs in a real course | Real TA differences; too small to expect much power |

## Pass criteria

Each applies to every dataset where the scenario can be run.

1. **False alarms.** With no real difference, random and unfair piles, it says the TAs differ in at most 10% of
   deals (a true 5% rate can show up to about 12% on 40 deals).
2. **Honest intervals.** At least 90% of 95% intervals contain the truth, in every scenario.
3. **Finds a real bias.** A planted 10-point gap on unfair piles is found in at least 90% of deals, with
   severity error of at most 3 points.
4. **Improves grades.** On that same scenario, correction gets more letter grades right than the marks as given.
5. **Re-check list beats the obvious.** On that same scenario, its precision beats listing the same number of
   scripts nearest a grade boundary.
6. **The chance it shows is honest.** On that same scenario, the mean chance shown is within 15 percentage points
   of the share that truly changed.
7. **Robust.** No damaged or awkward file crashes it. Renaming columns, re-sorting rows and changing line endings
   move severity by 0.00. No identifier survives in an answer.

CityU has too few answers, so criteria 3 to 6 are reported but not judged there.

**Corrections from opening the data, 4 November 2026, before any result was read.** CityU's question 6 was published
with only two TAs' marks, so the test uses its other 5 questions: 200 answers, not 240. JorGPT has 3,031 usable answers,
not 3,041, because 10 rows have no language-model grade. Its only second opinion is that language model, so scenarios
using it are labelled as a teacher against a model, not two people. ASAP essays has 12,977 usable essays, not 12,978.

## What these cannot show

These are real answers and real grades, but the dealing of scripts to TAs is done by the test, and so is any
planted gap. None of them is an IITM course. Passing is evidence that the method works on real grading behaviour;
it is not proof that it works on every course, and no dataset could be.
