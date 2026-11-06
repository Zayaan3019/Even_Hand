# How accurate Even Hand is, and how we know

The pass marks were written down before the results were read, in
[PREREGISTRATION.md](PREREGISTRATION.md), and were not moved afterwards. Every failure is reported here, and every
change made after a result was read is dated there, with what had been seen first.

All figures on this page come from one run: GitHub Actions run
[42124687822](https://github.com/Zayaan3019/Even_Hand/actions/runs/42124687822) on commit `a6ef30b`. Later commits change
only documents and the wording of one sentence in the report, not any calculation. The validation code lives in `eh-validation`, which is never packaged into the product. It downloads each public
dataset itself, checks its SHA-256, and keeps nothing in the repository.

## In short

- **It does not raise false alarms.** With no real difference between TAs, it said they differed in at most 8% of
  deals on every dataset, with random piles and with piles of unequal strength. The limit was 10%.
- **Its intervals hold, with one exception.** At least 96% of 95% intervals contained the true difference in every
  scenario on every dataset but one: on JorGPT, with no difference and piles of unequal strength, 85% did in English
  and 81% in Spanish, short of the 90% limit. Those misses were too small to be reported as differences: false
  alarms there were 2% and 4%.
- **A planted gap on piles of unequal strength is found on some datasets and not others.** It was
  found in every deal on JorGPT (English and Spanish) and on the held-out ASAP essays, with errors of 0.3 to 1.1
  points. It was found in 57% of deals on UNT, 16% on CityU and none on ASAP short answers, against a limit of 90%.
  Where it is not found, the marks are left as they are: Even Hand never corrects without evidence.
- **When it corrects, grades improve.** On the planted gap, correction took the share of letter grades right from
  79.8% to 96.4% on JorGPT, 90.8% to 96.5% on the essays and 70.6% to 78.6% on UNT. Its re-check list beat listing
  the scripts nearest a grade boundary wherever it issued one.
- **When piles are dealt at random, it finds real differences.** On UNT it found the two real graders' 4.7-point
  difference in 98% of deals, and four TAs' differences in 98%.
- **It survives damaged files.** No file in 16 kinds of damaged or awkward file crashed it, on any of the three
  datasets tried. Re-sorting rows, renaming columns and changing line endings changed nothing. One file too large
  for the memory available was refused, with what to do.

## The datasets

| Dataset | Answers | Graded by | Role |
|---|---|---|---|
| UNT short answers (Mohler, Bunescu and Mihalcea, ACL 2011) | 2,273 to 81 questions | two people each | Development: settings were tuned on its even-numbered questions; figures use the odd-numbered ones |
| JorGPT (CC BY 4.0), English | 3,031 by 79 real students | a teacher and a language model | Development: its results were read before the last changes |
| JorGPT in its original Spanish | the same | the same | Development; a language the text model was not built for |
| CityU Operating Systems | 200 to 5 questions | three real TAs each | Development; too small for criteria 3 to 6 to be judged |
| ASAP short answers (CC BY 4.0) | 17,207 to 10 questions | two people each | Held out: nothing was tuned on it |
| ASAP essays (CC BY 4.0) | 12,977 essays | two raters each | Held out; answers far longer than the model reads |

CityU's answers have no licence for redistribution, so only aggregate figures appear here.

**How a test works.** Each deal sends every *script* (one student's answers to one paper) to one TA only, as an
IITM course splits its scripts, and keeps only that TA's marks. The double grading, or a gap the test plants, says
what the true difference is. Each deal goes through the real file reader and `Pipeline.run` with the product's
default settings: what a professor would get. ASAP deals draw 400 whole scripts per question afresh from the full
dataset, the size of a large course.

**The scenarios.**

- *Two real graders, random deal*: the dataset's two graders, scripts dealt at random.
- *No difference*: one real grader's marks under two TA names. The truth is no difference.
- *Planted 10-point gap*: one real grader's marks, one TA's made 10 points harsher.
- *Four TAs*: the real graders and a 10-point harsher copy of each (random deal), or one grader's marks with offsets
  +5, 0, -5 and -10 points (unfair piles).
- *Small 3-point gap*: one TA 3 points harsher, which is inside the 1.5-point allowance per TA, so finding it is not
  expected.
- *Unfair piles* deal the stronger scripts mostly (80/20) to one side, as when piles follow sections or departments.

## Against the pass marks

| Criterion | UNT short answers (development) | JorGPT, English (development) | JorGPT, Spanish (development) | CityU Operating Systems (development; criteria 3-6 not judged) | ASAP short answers (held out) | ASAP essays (held out) |
|---|---|---|---|---|---|---|
| 1. False alarms at most 10% (no difference: random, unfair) | Pass: 1%, 0% | Pass: 1%, 2% | Pass: 1%, 4% | Pass: 8%, 7% | Pass: 5%, 3% | Pass: 0%, 0% |
| 2. 95% intervals contain the truth at least 90% | Pass: lowest 99% | **Fail**: lowest 85% | **Fail**: lowest 81% | Pass: lowest 96% | Pass: lowest 100% | Pass: lowest 100% |
| 3. Planted gap on unfair piles found at least 90%, error at most 3 | **Fail**: found 57%, error 1.66 | Pass: found 100%, error 0.95 | Pass: found 100%, error 1.06 | Not judged: found 16%, error 4.14 | **Fail**: found 0%, error 1.64 | Pass: found 100%, error 0.31 |
| 4. Correction gets more grades right | Pass: 70.6% to 78.6% | Pass: 79.8% to 96.4% | Pass: 79.9% to 96.0% | Not judged: 88.8% to 88.8% | **Fail**: 94.8% to 94.8% | Pass: 90.8% to 96.5% |
| 5. Re-check list beats nearest-boundary | Pass: 100% against 37% | Pass: 79% against 37% | Pass: 79% against 37% | Not judged: 95% against 0% | **Not met**: no gap found, so no list | Pass: 100% against 11% |
| 6. Chance shown within 15 points of the truth | Pass: 89% shown, 100% true | Pass: 85% shown, 79% true | Pass: 86% shown, 79% true | Not judged: 95% shown, 95% true | **Not met**: no gap found, so no list | Pass: 97% shown, 100% true |
| 7. Robust to damaged and awkward files | Pass: 16 kinds of damage | Pass: 16 kinds of damage | Not run | Not run | Pass: 16 kinds of damage; refused with a reason: ten times the size, new roll numbers | Not run |

Error is the root-mean-square error of each TA's severity, in percentage points of a question's maximum. Criteria
3 to 6 are measured on the planted gap on unfair piles. Where no deal found the gap, no re-check list was made,
so criteria 5 and 6 are not met.

**Why criterion 3 fails on UNT and ASAP short answers.** Whether a TA was harsh or simply received weaker scripts
can only be told apart by reading the answers. When piles are of unequal strength, Even Hand takes out the part of
the gap the answers explain. How much of the rest to take out is an assumption the data cannot check, so each
interval runs from removing only the gap the answers show to assuming that what they cannot show differs between
the piles in the same proportion as what they can (Oster 2019). A difference is reported only if it holds across
that range. That keeps false alarms on
unfair piles at 0 to 7%, and costs power: a real gap is found only when it is large next to the uncertainty about
pile strength. On JorGPT and the essays it was, every time. On ASAP short answers, marked out of 2 or 3, a 10-point
gap is a third of a mark or less, and none was found.

**Why criterion 2 fails on JorGPT.** Not yet explained. It is in one scenario only, no difference on unfair piles,
and the intervals that missed did not lead to a reported difference.

## Full figures

**UNT short answers** (development; 100 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | -4.7 | **0.73** | 100% | 98% | 55.5%, 56.8% |
| No difference, random deal | +0.0 | **0.81** | 100% | 1% | 100.0%, 100.0% |
| No difference, unfair piles | +0.0 | **1.53** | 100% | 0% | 100.0%, 100.0% |
| Planted 10-point gap, unfair piles | -4.7 | **1.66** | 100% | 57% | 70.6%, 78.6% |
| Four TAs, random deal | -9.5 | **1.61** | 99% | 98% | 49.9%, 57.0% |
| Four TAs, unfair piles | -5.0 | **2.50** | 100% | 6% | 74.1%, 74.6% |
| Small 3-point gap, random deal | -1.4 | **0.80** | 100% | 20% | 86.4%, 88.3% |

**JorGPT, English** (development; 100 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | -1.3 | **0.93** | 100% | 12% | 89.5%, 89.1% |
| No difference, random deal | +0.0 | **0.95** | 100% | 1% | 100.0%, 99.9% |
| No difference, unfair piles | +0.0 | **1.73** | 85% | 2% | 100.0%, 99.6% |
| Planted 10-point gap, unfair piles | -4.6 | **0.95** | 99% | 100% | 79.8%, 96.4% |
| Four TAs, random deal | -6.1 | **2.18** | 96% | 79% | 65.8%, 81.1% |
| Four TAs, unfair piles | -7.1 | **1.62** | 99% | 98% | 77.6%, 93.0% |
| Small 3-point gap, random deal | -1.4 | **1.04** | 100% | 6% | 94.1%, 94.1% |

**JorGPT, Spanish** (development; 100 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | -1.3 | **0.93** | 100% | 9% | 89.5%, 89.1% |
| No difference, random deal | +0.0 | **0.96** | 100% | 1% | 100.0%, 99.8% |
| No difference, unfair piles | +0.0 | **1.90** | 81% | 4% | 100.0%, 99.3% |
| Planted 10-point gap, unfair piles | -4.6 | **1.06** | 99% | 100% | 79.9%, 96.0% |
| Four TAs, random deal | -6.1 | **2.20** | 96% | 76% | 65.8%, 80.6% |
| Four TAs, unfair piles | -7.1 | **1.59** | 100% | 98% | 77.6%, 93.5% |
| Small 3-point gap, random deal | -1.4 | **1.11** | 100% | 8% | 94.1%, 94.1% |

**CityU Operating Systems** (development; criteria 3-6 not judged; 100 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | -0.5 | **2.35** | 99% | 5% | 92.2%, 91.0% |
| No difference, random deal | +0.0 | **2.75** | 100% | 8% | 100.0%, 97.9% |
| No difference, unfair piles | +0.0 | **3.75** | 100% | 7% | 100.0%, 98.6% |
| Planted 10-point gap, unfair piles | -4.6 | **4.14** | 100% | 16% | 88.8%, 88.8% |
| Four TAs, random deal | -5.2 | **3.79** | 98% | 58% | 70.7%, 70.3% |
| Four TAs, unfair piles | -6.0 | **6.76** | 96% | 8% | 86.2%, 84.5% |
| Small 3-point gap, random deal | -1.4 | **2.11** | 100% | 9% | 82.1%, 81.7% |
| Three real TAs, random deal | -1.3 | **3.14** | 100% | 6% | 90.3%, 89.2% |

**ASAP short answers** (held out; 40 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | -0.1 | **0.54** | 100% | 10% | 90.5%, 89.8% |
| No difference, random deal | +0.0 | **0.61** | 100% | 5% | 100.0%, 99.4% |
| No difference, unfair piles | +0.0 | **1.65** | 100% | 3% | 100.0%, 99.9% |
| Planted 10-point gap, unfair piles | -3.0 | **1.64** | 100% | 0% | 94.8%, 94.8% |
| Four TAs, random deal | -3.0 | **0.95** | 100% | 95% | 81.6%, 75.9% |
| Four TAs, unfair piles | -5.2 | **2.05** | 100% | 3% | 83.2%, 83.1% |
| Small 3-point gap, random deal | -0.9 | **0.58** | 100% | 53% | 89.9%, 90.8% |

**ASAP essays** (held out; 20 deals per scenario)

| Scenario | True severity, first TA | Error (RMSE) | Intervals hold | Says the TAs differ | Grades right: as marked, after |
|---|---|---|---|---|---|
| Two real graders, random deal | +0.1 | **0.29** | 100% | 5% | 80.7%, 80.6% |
| No difference, random deal | +0.0 | **0.25** | 100% | 0% | 100.0%, 100.0% |
| No difference, unfair piles | +0.0 | **0.57** | 100% | 0% | 100.0%, 100.0% |
| Planted 10-point gap, unfair piles | -4.9 | **0.31** | 100% | 100% | 90.8%, 96.5% |
| Four TAs, random deal | -4.8 | **0.53** | 100% | 100% | 75.6%, 79.3% |
| Four TAs, unfair piles | -7.2 | **0.68** | 100% | 100% | 84.8%, 96.1% |
| Small 3-point gap, random deal | -1.5 | **0.24** | 100% | 95% | 94.1%, 98.0% |

"Says the TAs differ" is the share of deals where the report found a difference (p < 0.05 at both ends of the
interval). With no real difference, that share is the false-alarm rate. "Grades right" compares each script's
letter grade with the letter its marks would earn from the average of the TAs.

**Against comparing pile averages.** When piles are of unequal strength, simply comparing TAs' average marks is
badly wrong. On UNT, with the instructor's answer key (error in points, 100 deals each):

| Scenario | Pile averages | **Even Hand** |
|---|---|---|
| Two real graders, random deal | 0.67 | **0.83** |
| No difference, random deal | 1.00 | **0.86** |
| No difference, unfair piles | 5.28 | **1.53** |
| Planted 10-point gap, unfair piles | 4.98 | **1.68** |
| Four TAs, random deal | 1.39 | **1.60** |
| Four TAs, unfair piles | 5.21 | **2.67** |

## The same file under a different course key

Each course gets its own secret key, which turns roll numbers into labels, and the random draws follow those
labels. The same file under the same key always gives the same report. Each deal below was run under 30
different keys (10 deals per scenario; 5 on ASAP short answers and 3 on essays, to fit GitHub's time limit):

| Dataset | Scenario | Deals where "the piles differ" flipped | Severity range across keys, mean (points) | Largest | Deals where the re-check list came and went |
|---|---|---|---|---|---|
| UNT short answers | Two real graders, random deal | 1 of 10 | 0.20 | 1.63 | 0 of 10 |
| UNT short answers | No difference, random deal | 1 of 10 | 0.23 | 1.96 | 2 of 10 |
| UNT short answers | No difference, unfair piles | 0 of 10 | 0.52 | 0.66 | 0 of 10 |
| UNT short answers | Planted 10-point gap, unfair piles | 0 of 10 | 0.49 | 0.57 | 4 of 10 |
| UNT short answers | Four TAs, random deal | 1 of 10 | 0.27 | 2.69 | 0 of 10 |
| CityU Operating Systems | Two real graders, random deal | 1 of 10 | 0.94 | 6.49 | 2 of 10 |
| CityU Operating Systems | No difference, random deal | 4 of 10 | 2.52 | 7.70 | 2 of 10 |
| CityU Operating Systems | No difference, unfair piles | 0 of 10 | 3.50 | 7.52 | 2 of 10 |
| CityU Operating Systems | Planted 10-point gap, unfair piles | 0 of 10 | 3.42 | 7.51 | 3 of 10 |
| CityU Operating Systems | Four TAs, random deal | 2 of 10 | 2.91 | 16.07 | 5 of 10 |
| ASAP short answers | Two real graders, random deal | 0 of 5 | 0.02 | 0.11 | 0 of 5 |
| ASAP short answers | No difference, random deal | 1 of 5 | 0.27 | 1.37 | 2 of 5 |
| ASAP short answers | No difference, unfair piles | 0 of 5 | 0.24 | 0.33 | 0 of 5 |
| ASAP short answers | Planted 10-point gap, unfair piles | 0 of 5 | 0.21 | 0.32 | 0 of 5 |
| ASAP short answers | Four TAs, random deal | 0 of 5 | 0.00 | 0.00 | 0 of 5 |
| ASAP essays | Two real graders, random deal | 0 of 3 | 0.00 | 0.00 | 0 of 3 |
| ASAP essays | No difference, random deal | 0 of 3 | 0.00 | 0.00 | 0 of 3 |
| ASAP essays | No difference, unfair piles | 0 of 3 | 0.20 | 0.24 | 0 of 3 |
| ASAP essays | Planted 10-point gap, unfair piles | 0 of 3 | 0.18 | 0.24 | 0 of 3 |
| ASAP essays | Four TAs, random deal | 0 of 3 | 0.00 | 0.00 | 0 of 3 |

Where the evidence sits near the line, a different key can tip "the piles differ" one way or the other. On UNT and
the ASAP sets a different key moves a TA's severity by 0.0 to 0.5 points on average; on CityU, with only 40
students, by 0.9 to 3.5. The report shows close calls as close calls.

## What was wrong, and was fixed

Every one of these was found by this validation, and each is dated in [PREREGISTRATION.md](PREREGISTRATION.md).

- **False alarms on unfair piles** (80 to 99% of deals on JorGPT and CityU). The Hausman test choosing between the
  marks and the answers compared two estimates sharing most of the same bias. It was replaced by a direct test of
  whether the piles differ in strength.
- **Intervals several times too wide.** The pile gap and the marks' severity were drawn from different resamples of
  students. They are now drawn from the same ones.
- **About 1,860 ASAP grades changed by noise.** A 0.01-point correction moved grades. Now nothing is corrected
  without evidence of a difference.
- **Re-sorting a file's rows changed 455 ASAP grades.** Questions were numbered by first appearance. They are now
  numbered in a fixed order, so the same marks in any order give the same report.
- **A class of 40,000 students ran out of memory.** The exact audit's memory grows with the square of the class.
  A class too large is now refused at the start, with what to do.

## Limits

- **None of these is an IITM course.** IITM answers, with numerical working and diagrams described in words, may
  carry less signal in their text. Every report shows how much the answers explain, so this is visible on each run.
- **Unequal piles limit detection.** Where one TA's pile is stronger, a real but moderate difference is often not
  reported (criterion 3). Dealing scripts at random, or mixing sections across TAs, makes TAs comparable from the
  marks alone.
- **Close calls depend on the key.** Near the line, the verdict can differ between course keys (see above).
- **Scanned answer sheets are not read.** Answers must be text. Notation and equations are read as text only.
- **Size.** The exact audit needs memory that grows with the square of the class: about 0.3 GB for 5,000
  students. Larger classes are refused with advice, such as checking each section as its own file.

## The answer reader is the model it claims to be

- **Exactness.** Even Hand runs all-MiniLM-L6-v2 with its own Java implementation (`eh-embed`). On 12 reference
  answers, chosen to include accents, Greek, CJK, line breaks and an answer longer than the model reads, the word
  pieces are identical to Hugging Face's tokenizer, and every vector matches `sentence-transformers` 5.2.0 to
  cosine above 0.99999. This is checked on every build (`TransformerEmbedderTest`).
- **Integrity.** The model file is checked against a pinned SHA-256 before use. A different file is reported and
  not used.

## The software

- **235 Java tests** across 10 modules, and **20 web app tests**, all passing on every build. They cover ingest of
  every file layout, including Excel with zip-bomb and XXE attempts, the audit's exact rank, the estimators against
  known answers, the pile test and its paired draws, grading rules, the reports, and the server's security rules.

## Reproduce

```
gh workflow run validate.yml --ref <branch>   # every dataset, on GitHub Actions: about two hours
./mvnw verify                                 # every test, Java and web
```
