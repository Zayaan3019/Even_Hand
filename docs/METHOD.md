# How Even Hand decides that a TA, and not the answers, caused a difference in marks

This is the method behind sections 2–4 of the instructor report. It is written for a reader who
wants to check the reasoning, not only the result. Every figure quoted here is measured in
[`VALIDATION.md`](VALIDATION.md).

## 1. The question is causal

Two TAs' piles get different average marks. There are two possible reasons, and they call for
opposite responses:

- **The students in one pile wrote better answers.** A legitimate difference: nothing to correct.
- **One TA marked the same work differently.** A marking effect: the grades it moved deserve a
  second look.

Comparing pile averages cannot tell these apart. It is a correlation between "who marked" and
"what was awarded", confounded by "what was written". Even Hand asks the counterfactual question
instead: **what would this answer have been given by another TA?**

In the language of causal inference (Rubin's potential outcomes), each answer is a unit. The TA who
marked it is the treatment, and the mark it would receive from each TA is a potential outcome. A
TA's *severity* is the average difference between the mark they gave and the mark the average TA
would have given **the same answer**.

## 2. Two ways to identify it

**From the marking design (marks only, `eh-estimate`).** When scripts are marked by more than one
TA, or questions are rotated between TAs, the marks alone separate a TA's severity from student
ability: the audit proves this with exact integer rank (`eh-design`). When each TA marks a separate
pile of whole scripts (the usual IITM arrangement), the marks alone can compare TAs only by
**assuming the piles were alike in ability**. That holds when piles are dealt at random, and fails
when they follow sections, departments or roll-number blocks.

**From the answers (`eh-content`).** The mark is supposed to depend on one thing only: what the
student wrote. So once two answers say the same thing, what was written can no longer explain a
difference in their marks. Whatever remains is caused by who marked them. Formally, the
assumption is **conditional ignorability given the answer**: within answers of the same content,
which TA marked an answer is unrelated to how it would have been marked. It needs no assumption
about how piles were dealt. It does need the answers' content to be captured well enough, and
every check in section 4 measures how far that holds.

## 3. The estimator

For every question with written answers:

1. **Answers as vectors.** A sentence-embedding model (all-MiniLM-L6-v2, run by our own Java
   forward pass, `eh-embed`) turns each answer into a unit vector. Answers that make the same points
   have nearby vectors.
2. **A content model (prognostic score; Hansen 2008).** It predicts the mark an answer should
   earn from what it says, in two layers:
   - A model shared by all questions, on the answer's similarity to the instructor's key and to
     the class's consensus answer (`PooledQuality`).
   - Per question, a ridge regression on the embedding for what that adds (`ContentModel`).

   Both have one intercept per TA, so they learn only from variation *within* a TA's pile and
   cannot absorb severity (Frisch–Waugh–Lovell). Both are cross-fitted on student folds, so no
   answer predicts itself.
3. **Matching.** Each answer is paired with the 3 most similar answers marked by *other* TAs
   (cosine ≥ 0.60). The pair's mark gap, minus the content model's estimate of what the two answers
   say differently, is evidence about the two TAs alone. This is the bias-corrected matching
   estimator of Abadie and Imbens (2011).
4. **Severities.** The pairs are weighted by the question's marks and solved by least squares
   over the graph of TAs, separately for each group of TAs the answers link. Each group's
   severities average zero (`SeverityGraph`).
5. **Uncertainty.** A multiplier bootstrap reweights whole students. Each pair gets the
   *product* of its two students' weights, which gives a pair statistic its correct first-order
   variance; averaging the two weights halved it, and calibration caught that. Intervals are then
   widened by 1.5 points on each side, a **bias-aware interval** (Armstrong and Kolesár 2020). On
   real double-graded answers with unfairly dealt piles, a residual bias of about that size
   remains, which no resampling can see.
6. **The no-effect test** asks whether the TAs differ by more than **1.5 points** (0.15 marks on a
   10-mark question), the smallest difference worth reporting, rather than by more than exactly zero.
   A residual bias smaller than the margin therefore cannot become an accusation.

**Which estimate corrects the grades (`ContentFit.choose`).**

- When the marks alone are assumption-free, they are used, and the answers serve as an
  independent check.
- When the marks need the equal-piles assumption, the two estimates are compared by a **Hausman
  (1978) test**, at the 10% level: wrongly trusting the marks when piles differed costs several points,
  while wrongly preferring the answers costs a little precision:
  - If they agree, the answers have confirmed the assumption, and the more precise marks
    estimate is used.
  - If they disagree, the piles were not alike, and the answers' estimate is used. The report
    says how far the marks alone would have misled.

## 4. Why the conclusion can be trusted, or not

The report shows each of these checks, with a green or amber mark:

| Check | What it rules out | Reference |
|---|---|---|
| **Decomposition** of each TA's raw gap into *what students wrote* + *how it was marked* + *neither* | Mistaking a stronger pile for a lenient TA | Oaxaca–Blinder-style decomposition |
| **Overlap**: share of each TA's answers with a close match marked by someone else | Comparing a TA with nobody (positivity) | Imbens and Rubin 2015, ch. 14 |
| **Balance**: standardised difference in predicted quality within matched pairs (< 0.25 with regression adjustment) | Unlike being compared with unlike | Rubin 2001; Stuart 2010 |
| **Negative control**: answer length within matched pairs. A TA cannot change it and nothing adjusts for it. | Residual confounding the content model misses | Lipsitch et al. 2010 |
| **Same script, two TAs**: scripts marked twice compare TAs on identical content | Any failure of the content model, since content is identical by construction | — |
| **Placebo**: TA labels shuffled among answers of similar quality, and the analysis rerun | A method that finds effects where there are none | Falsification test |
| **Sensitivity (Rosenbaum Γ)**: how strongly hidden quality differences would have to favour one side of every pair to overturn the conclusion | Over-reliance on the embedding | Rosenbaum 2002 |

## 5. What kind of difference it is

For each TA, the per-question effects are tested for heterogeneity (Cochran's Q), and the residuals
against the marking order (when the file records it):

| Reading | Meaning | What to do |
|---|---|---|
| **Consistent severity** | Harsher (or more lenient) by a similar amount on every question | The grade correction applies as a whole |
| **Rubric difference** | Concentrated in one or two questions | Discuss that question's rubric with the TA |
| **Drift** | Marks moved through the pile, for answers of the same quality | Check the late scripts |
| **Isolated slips** | No overall difference, but a few marks far out of line with similar answers | Check those scripts individually |
| **No clear difference** | Within chance | Nothing |

## 6. What it cannot do

- **Images.** The answers must be text; scanned answer sheets are the next phase.
- **Correctness the embedding cannot see.** Two answers can be close in embedding space and differ
  in correctness ("BC is in tension" and "BC is in compression"). The content model, the bias
  allowance and the sensitivity analysis exist because of this, and the near-identical-answer
  evidence is shown so a person can judge.
- **Question-wise marking.** When one TA marked every answer to a question, no two TAs ever marked
  similar answers, so nothing compares them. The report says so; that arrangement also cannot move
  anyone relative to anyone else.
- **It never changes a mark.** Every finding is a recommendation to look again.

## References

- Abadie, A. and Imbens, G. (2011). Bias-corrected matching estimators for average treatment effects. *JBES* 29(1).
- Armstrong, T. and Kolesár, M. (2020). Simple and honest confidence intervals in nonparametric regression. *Quantitative Economics* 11(1).
- Chernozhukov, V. et al. (2018). Double/debiased machine learning for treatment and structural parameters. *Econometrics Journal* 21(1).
- Hansen, B. (2008). The prognostic analogue of the propensity score. *Biometrika* 95(2).
- Hausman, J. (1978). Specification tests in econometrics. *Econometrica* 46(6).
- Imbens, G. and Rubin, D. (2015). *Causal Inference for Statistics, Social, and Biomedical Sciences.* Cambridge.
- Lipsitch, M., Tchetgen Tchetgen, E. and Cohen, T. (2010). Negative controls. *Epidemiology* 21(3).
- Mohler, M., Bunescu, R. and Mihalcea, R. (2011). Learning to grade short answer questions using semantic similarity measures and dependency graph alignments. *ACL*.
- Reimers, N. and Gurevych, I. (2019). Sentence-BERT. *EMNLP*.
- Rosenbaum, P. (2002). *Observational Studies*, 2nd ed. Springer.
- Rubin, D. (2001). Using propensity scores to help design observational studies. *Health Services and Outcomes Research Methodology* 2.
- Stuart, E. (2010). Matching methods for causal inference: a review. *Statistical Science* 25(1).
