# How accurate Even Hand is, and how we know

Every claim below is measured, and every measurement can be reproduced with one command. The
validation code lives in `eh-validation`, which is never packaged into the product: professors only
ever see their own data.

## 1. On real answers graded twice by real people

**The data.** The UNT Computer Science Short Answer Dataset v2.0 (Mohler, Bunescu and Mihalcea, ACL
2011) has 2,273 real student answers to 81 questions from a data-structures course. Each answer was
graded **independently by two people**: the course's TA and a second grader. Because both graded
everything, how differently they graded is known exactly. That is the ground truth a real course
never has.

**The test.** Each run deals every *script* (one student's answers to one assignment) to one grader
only, as an IITM course splits its scripts. It keeps only that grader's marks and asks Even Hand to
recover the difference the double grading reveals. Each scenario has 100 random deals. The matching
settings were tuned on the even-numbered questions, and every figure here is measured on the
odd-numbered ones, which were never used for tuning.

| Scenario | What is true | Pile averages | Marks only | **Even Hand** | 95% intervals contain the truth | Says "the TAs differ" |
|---|---|---|---|---|---|---|
| Two real graders, random deal | the TA is 4.7 points more lenient | 0.67 | 0.68 | **0.76** | 100% | 97% |
| No difference, random deal | no difference | 1.00 | 0.82 | **0.77** | 100% | **0%** (no false alarm) |
| No difference, **unfair piles** | no difference | 5.28 | 4.94 | **1.53** | 99% | **0%** (no false alarm) |
| Planted 10-point gap, **unfair piles** | known gap | 4.98 | 4.53 | **1.61** | 96% | 100% |
| Four TAs, random deal | known offsets | 1.39 | 1.47 | **1.47** | 97% | 100% |
| Four TAs, **unfair piles** | known offsets | 5.21 | 4.08 | **2.05** | 93% | 99% |

Figures are root-mean-square error in percentage points of a question's maximum, over every TA in
every run, with the instructor's answer key given. Without the key they are within 0.04 of these.
"Unfair piles" deal the stronger scripts mostly (80/20) to one side, as when piles follow sections
or departments. Full tables, including bias, the content signal and the placebo:
[validation/VALIDATION-REAL.md](validation/VALIDATION-REAL.md).

**What it shows.**

- **When piles differ in quality, comparing pile averages is badly wrong (5 points), and so are the
  marks alone.** Comparing answers that say the same thing brings the error down to 1.5–2 points.
  That is the case the answers exist for.
- **When piles were dealt at random, the marks alone are already good.** The Hausman test then
  keeps them, using the answers' estimate in only about 10% of runs, so the answers cost almost nothing.
- **No false alarms.** With no real difference between graders, Even Hand reported one in 0–1% of
  runs, including with unfair piles. This is what the 1.5-point smallest-difference margin and the
  bias-aware intervals are for.
- **Intervals hold**: 93–100% of 95% intervals contain the truth in every scenario.

**Marks out of line.** With 2% of marks replaced by an obvious slip, the out-of-line check finds
about a quarter of them, and 43% of its flags are real slips. Real graders disagree a lot on a 0–5
scale, so a single mark is hard to call wrong. The report therefore presents these as scripts to
look at, never as errors.

## 2. On simulated courses with known answers

`eh-content`'s calibration probe repeats the analysis on 100 simulated courses of each kind. The
answers are vectors with a known meaning, so the truth is exact. The probe covers piles dealt at
random and piles sorted by ability, the hardest case for comparing piles:

| Piles | False alarms (no real difference) | Real differences found | 95% interval coverage | Error |
|---|---|---|---|---|
| Random | 0 / 100 | 100 / 100 | 100% | 0.54 points |
| Sorted by ability | 0 / 100 | 100 / 100 | 100% | 0.74 points |

How the calibration caught real problems while the method was built:

- The first bootstrap averaged the two students' weights for each pair. It covered only 74% (random)
  and 45% (sorted), and raised false alarms in 27/60 runs. Product weights fixed it.
- The first placebo used coarse quality strata and flagged confounding as an effect.

The marks-only estimator has its own benchmark on 112 simulated IITM courses of seven shapes:
[BENCHMARK.md](BENCHMARK.md).

## 3. The answer reader is the model it claims to be

- **Exactness.** Even Hand runs all-MiniLM-L6-v2 with its own Java implementation (`eh-embed`). On
  12 reference answers, chosen to include accents, Greek, CJK, line breaks and an answer longer than
  the model reads, the word pieces are identical to Hugging Face's tokenizer, and every vector matches
  `sentence-transformers` 5.2.0 to cosine above 0.99999. This is checked on every build
  (`TransformerEmbedderTest`).
- **Integrity.** The model file is checked against a pinned SHA-256 before use. A different file is
  reported and not used.
- **Speed.** About 70 answers a second on a laptop (Core Ultra 7 155H) with the SIMD kernels; 2,273
  answers take 15 seconds.

## 4. The software

- **Java tests (210).** They cover every module: ingest of every file layout, including Excel, with
  zip-bomb and XXE attempts; the audit's exact rank; the estimators against known answers; the
  Hausman choice; grading rules; the reports; and the server's security rules (Origin, Host, path
  traversal, Content-Security-Policy).
- **Web app tests (18).** Vitest checks the components rendering the server's exact JSON, the name
  handling, file checks and progress stages. Strict TypeScript is checked on every build.
- **Browser.** The full flow was exercised in Chrome on the real data above, as both .csv and .xlsx:
  upload, the review step, live progress, every dashboard section, the names switch, save, reset, and
  an unreadable file.

## 5. What is not yet validated

- **Real IITM marks.** The dataset is a US computer-science course. IITM answers (numerical working,
  diagrams described in words) may carry less signal in their text; the report shows the content
  signal for every run so this is visible.
- **Scanned answer sheets** are not read; the answers must be text.
- **Hand-written notation and equations** in typed answers are read as text only.

## Reproduce

```
sh scripts/validate.sh 100      # real data: downloads the dataset once (SHA-256 pinned), ~20 minutes
./mvnw verify                   # every test, Java and web
```
