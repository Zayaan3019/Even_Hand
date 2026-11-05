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

## First results, and the fix they led to (4 November 2026)

Scored against the criteria above, on commit 2b0b5fd, with thresholds unchanged. The severity error in criterion 3 is
the table's RMSE. "Share truly changed" in criterion 6 is the re-check list's precision.

| Criterion | JorGPT, English | JorGPT, Spanish | CityU |
|---|---|---|---|
| 1. False alarms at most 10% | **Fail**: 3% random, 85% unfair | **Fail**: 2% random, 80% unfair | **Fail**: 7% random, 99% unfair |
| 2. Coverage at least 90% | **Fail**: 30%, 51% and 74% on unfair piles | **Fail**: 33%, 53% and 70% on unfair piles | **Fail**: 2%, 1% and 4% on unfair piles |
| 3. Finds 10-point gap, error at most 3 | **Fail**: found 100%, error 3.62 | **Fail**: found 100%, error 3.62 | Not judged: found 100%, error 13.31 |
| 4. Correction improves grades | **Fail**: 79.8% to 79.4% | **Fail**: 79.9% to 79.4% | Not judged: 88.8% to 59.2% |
| 5. Re-check list beats nearest boundary | Pass: 79% against 37% | Pass: 79% against 37% | Not judged: 75% against 0% |
| 6. Chance shown within 15 points | **Fail**: 97% shown, 79% true | **Fail**: 97% shown, 79% true | Not judged: 99% shown, 75% true |
| 7. Robust | Pass | Not run | Not run |

**Cause.** On these datasets the answers explain about a fifth of the marks. The Hausman test compared two estimates
that shared most of the same bias, saw no disagreement, and said the piles were comparable when they were not. A
pretest with little power is known to do this (Guggenberger 2010, *Econometric Theory* 26).

**The fix, fixed here before any ASAP result was read.** The choice between estimators no longer uses the Hausman test.

1. *Do the piles differ?* The mean out-of-sample content score of each TA's pile is compared, with whole students
   resampled, at the 10% level. The score is learned within piles, so no TA's severity enters it.
2. *If not,* the marks estimate is used, with the existing 1.5-point allowance.
3. *If so,* each TA's severity becomes `marks estimate + gap / R²`, where `gap` is the pile's content-score gap and `R²`
   is the squared correlation between content scores and marks across scripts, within piles, floored at the existing
   0.15. This takes what the answers cannot show to differ between piles in proportion to what they can (Altonji,
   Elder and Taber 2005; Oster 2019, with delta = 1 and maximum R² = 1). The same allowance applies.

That proportion is an assumption the data cannot check, and the report says so.

**What the new results can claim.** The fix was designed after reading the UNT, JorGPT and CityU results, so these are
now development data: their new results show whether it repaired the failure, not how it does on unseen data.
JorGPT's Spanish version shares its marks with the English one, so it is not independent either. **Both ASAP datasets
stay held out.** Their results on the old code were still running and unread when this was committed, and both those
and the new results will be reported. Any further change to the rule gets its own dated note here.

**Disclosure.** Before this file's corrections, a check of the loaded data displayed each question's mean gap between
graders and mean word count for ASAP essays, JorGPT and CityU. The message of commit 2b0b5fd says only that marks were
read for shape. No pipeline result was read at that point, and no threshold changed.

## ASAP at course size, and what was read first (6 November 2026)

**Why the size changed.** Neither ASAP dataset has ever finished. At full size (about 1,700 answers per question) the
old code's runs reached GitHub's 350-minute limit after two of seven scenarios for short answers and one for essays.
From this note on, each ASAP deal draws 400 whole scripts per question afresh from the full dataset, the size of a
large course, and each scenario runs as its own job. The number of deals per scenario, the scenarios and every
threshold are unchanged. The other datasets are small enough to run whole, as before.

**What was read before this note.** The rows the old code finished before the limit were read at about 02:00 on
6 November, after the fix above was committed and before this change:

| Old code (2b0b5fd), full size | Says TAs differ | Coverage | Grade right, as marked to corrected |
|---|---|---|---|
| Short answers, random (40 deals) | 8% | 100% | 90.6% to 81.6% |
| Short answers, null (40 deals) | 15% | 100% | 100.0% to 89.3% |
| Essays, random (20 deals) | 10% | 100% | 79.3% to 79.3% |

The short-answer null row fails criterion 1 (15% against at most 10%), and both short-answer rows fail criterion 4.
They are reported as the old code's results. On short answers the correction moved about 1,860 grades even in
deals where the TAs did not differ. The suspected cause, not yet confirmed: each script there is one answer marked
0 to 3, so most scripts tie with many others, and a relative grade boundary that falls on a tie lets a correction of a
hundredth of a point decide which side each tied script lands. This was seen before the new results and is reported,
not tuned away: the rule for the fix is unchanged.

The fix itself is untouched by anything read here. The new ASAP results remain the held-out test of it.

## No correction without evidence of a difference (6 November 2026, later)

**Cause, confirmed in the code.** The default rule gives S to the top 10%, admitting everyone tied at the cut, and a
corrected mark is held within zero and full marks. On ASAP short answers many scripts tie at full marks. A correction
of a hundredth of a point lowers one TA's full-mark scripts below the tie while the other TA's stay capped at full marks,
so a whole pile leaves S. The report already said no grade was attributed to the TAs in such runs, but its grade table
and the "Grade right after correction" column still used the corrected marks.

**Change.** Unless the no-effect test rejects at the re-check gate's existing 5% level, the corrected marks are now the
marks as written, no grade changes, and the report's count and range are 0. No threshold changed. This was prompted by
the old code's ASAP rows above, so criterion 4 on ASAP now partly reflects a change made after seeing ASAP; that is
stated wherever the result is reported. The pile-balance rule above is unchanged.
