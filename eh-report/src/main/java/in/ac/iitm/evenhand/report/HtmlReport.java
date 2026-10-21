package in.ac.iitm.evenhand.report;

import in.ac.iitm.evenhand.content.CausalFindings;
import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.outcome.Outcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 5: the reports, as single self-contained HTML files that open in any browser
 * with no network access, and print cleanly.
 *
 * <ul>
 *   <li>{@link #instructor} is for the instructor alone. It names assistants by
 *       pseudonym, with an interval on every figure, explains why marks differed, quotes the
 *       answers that show it, and lists the scripts worth a second look.</li>
 *   <li>{@link #committee} is for the Class Committee. It carries aggregates only: no
 *       assistant, no student and no answer appears in it.</li>
 * </ul>
 *
 * <p>The report adds no numbers of its own; every figure comes from an earlier stage.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String instructor(ReportInput in) {
        StringBuilder b = new StringBuilder();
        head(b, "Even Hand: " + in.courseLabel());
        b.append("<header class=top><div class=brand>Even Hand</div><div class=kind>grading-consistency report"
                + "</div></header>\n");
        b.append("<h1>").append(esc(in.courseLabel())).append("</h1>\n");
        b.append("<p class=meta>").append(in.design().studentCount()).append(" students &middot; ")
         .append(in.design().graderCount()).append(" TAs &middot; ")
         .append(in.design().questionCount()).append(" questions");
        in.content().ifPresent(c -> b.append(" &middot; ").append(pct(c.textCoverage()))
                .append(" of responses with written answers"));
        b.append(" &middot; <b>for the instructor only</b></p>\n");
        bottomLine(b, in);
        glance(b, in);

        section(b, "1. Can this marking arrangement be checked?");
        b.append("<p class=verdict>").append(esc(in.verdict().headline())).append("</p>\n");
        if (in.verdict() instanceof AuditVerdict.EstimableFromContent c) {
            b.append("<p>From who marked what alone: ").append(esc(c.fromMarking().headline()))
             .append(" <b>The written answers remove that limitation</b>: they compare TAs through answers that "
                     + "say the same thing, so no assumption about how the piles were dealt is needed.</p>\n");
        }
        b.append("<p>").append(esc(in.verdict().exposure().headline())).append("</p>\n");
        diagnostics(b, in);

        if (in.model().isEmpty()) {
            b.append("<p class=note>Severity was not estimated: the arrangement cannot support it. "
                    + "Nothing below about TAs or grades is computed, by design.</p>\n");
            in.content().ifPresent(c -> {
                contentStatus(b, c);
                evidence(b, c, "2");
            });
            footer(b, in);
            return b.toString();
        }
        FittedModel m = in.model().get();
        Outcome.Result o = in.outcome().orElseThrow();

        section(b, "2. How differently did the TAs mark?");
        b.append("<p>Positive means harsher than the average TA, negative more lenient, in "
                + "<b>percentage points of each question's maximum</b>: +5 means about half a mark less "
                + "on a 10-mark question. Bars are 95% intervals.");
        b.append(in.anchoredByAnswers()
                ? " Each figure compares answers that say the same thing, marked by different TAs."
                : " Each figure comes from the marks, with the students resampled for the interval.");
        b.append("</p>\n");
        severityChart(b, m);
        severityTable(b, m);
        b.append(String.format(Locale.ROOT,
                "<p>No-effect test: if the TAs marked alike, differences this large would arise with probability "
                + "<b>p = %.3f</b>. %s</p>%n", m.noEffect().pValue(),
                m.noEffect().rejectsAt(0.05) ? "The differences are unlikely to be chance."
                        : "The differences are within what chance alone produces."));
        crossCheck(b, in, m);

        int next = 3;
        if (in.content().isPresent() && in.content().get().causal().isPresent()) {
            causal(b, in.content().get(), m, String.valueOf(next++));
        } else if (in.content().isPresent()) {
            section(b, next++ + ". Why did the marks differ?");
            contentStatus(b, in.content().get());
        } else {
            section(b, next++ + ". Why did the marks differ?");
            b.append("<p class=note>This file has marks but no written answers, so the report can say <i>that</i> "
                    + "the TAs differed but not whether the answers explain it. Add each answer's text to the file "
                    + "(a column such as <code>Q1 answer</code>) and Even Hand will compare answers that say the same "
                    + "thing across TAs, and separate what students wrote from how it was marked.</p>\n");
        }
        if (in.content().isPresent()) evidence(b, in.content().get(), String.valueOf(next++));

        section(b, next++ + ". What did it do to grades?");
        b.append(String.format(Locale.ROOT,
                "<p><b>%.1f%%</b> of the spread in course totals came from who marked the script rather than "
                + "what was written. Correcting for it would change <b>%d</b> letter grade%s "
                + "(90%% range %d&ndash;%d).</p>%n",
                o.assistantShare(), o.changed(), o.changed() == 1 ? "" : "s", o.changedLow(), o.changedHigh()));
        gradeTable(b, o);

        section(b, next + ". Scripts worth a second look");
        switch (o.shortlist()) {
            case Outcome.Refused r -> b.append("<p class=note>No list: ").append(esc(r.reason())).append("</p>\n");
            case Outcome.Emitted e -> shortlistTable(b, e);
        }
        footer(b, in);
        return b.toString();
    }

    public static String committee(ReportInput in) {
        StringBuilder b = new StringBuilder();
        head(b, "Class Committee sheet: " + in.courseLabel());
        b.append("<header class=top><div class=brand>Even Hand</div><div class=kind>Class Committee sheet: "
                + "aggregate only</div></header>\n");
        b.append("<h1>").append(esc(in.courseLabel())).append("</h1>\n");
        b.append("<p class=meta>").append(in.design().studentCount()).append(" students &middot; ")
         .append(in.design().graderCount()).append(" TAs</p>\n");
        b.append("<p class=verdict>").append(esc(in.verdict().headline())).append("</p>\n");
        b.append("<p>").append(esc(in.verdict().exposure().headline())).append("</p>\n");
        in.content().flatMap(ContentResult::causal).ifPresent(f -> {
            b.append("<h2>Why marks varied</h2>\n");
            varianceBar(b, f.variance());
        });
        in.outcome().ifPresent(o -> {
            b.append(String.format(Locale.ROOT,
                    "<p><b>%.1f%%</b> of the spread in totals is attributable to the division of marking. "
                    + "Letter grades that would change on correction: <b>%d</b> (90%% range %d&ndash;%d).</p>%n",
                    o.assistantShare(), o.changed(), o.changedLow(), o.changedHigh()));
            gradeTable(b, o);
            b.append("<p>").append(o.shortlist() instanceof Outcome.Emitted e
                    ? e.rows().size() + " scripts have been recommended to the instructor for re-checking."
                    : "No scripts were recommended for re-checking.").append("</p>\n");
        });
        b.append("<p class=note>No grade has been changed by this tool. Any change is made by the instructor "
                + "after re-marking a script, within the revision window (R.24.1).</p>\n");
        b.append("</main></body></html>\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ summary

    /** The whole report in three sentences, with the one thing to do next. */
    static void bottomLine(StringBuilder b, ReportInput in) {
        b.append("<div class=bottom><b>Bottom line.</b> ");
        if (in.model().isEmpty()) {
            b.append("This way of dividing the marking cannot tell a harsh TA from a weaker group of students, "
                    + "so no TA is rated and no grade is questioned. Section 1 says why, and what would make "
                    + "it checkable next time.");
            in.content().ifPresent(c -> {
                int shown = c.sameAssistantInconsistent().size() + c.outOfLine().size();
                if (shown > 0) {
                    b.append(" The written answers still point to ").append(shown).append(" individual mark")
                     .append(shown == 1 ? "" : "s").append(" worth checking (section 2).");
                }
            });
            b.append("</div>\n");
            return;
        }
        FittedModel m = in.model().get();
        Outcome.Result o = in.outcome().orElseThrow();
        if (!m.noEffect().rejectsAt(0.05)) {
            b.append(String.format(Locale.ROOT, "The TAs marked consistently: their differences are within what "
                    + "chance alone produces (p = %.2f). No script needs a second look on these grounds.",
                    m.noEffect().pValue()));
            in.content().flatMap(ContentResult::causal).ifPresent(f -> b.append(String.format(Locale.ROOT,
                    " Of the variation in marks, %.0f%% reflects what students wrote.", f.variance().content())));
            b.append("</div>\n");
            return;
        }
        FacetEstimates g = m.graders();
        int harsh = 0, lenient = 0;
        for (int j = 1; j < g.size(); j++) {
            if (g.estimates()[j] > g.estimates()[harsh]) harsh = j;
            if (g.estimates()[j] < g.estimates()[lenient]) lenient = j;
        }
        String basis = in.anchoredByAnswers() ? " on answers saying the same thing" : "";
        if (m.groups().size() == 1) {
            b.append(String.format(Locale.ROOT, "The TAs did not mark alike%s. %s was the harshest (%s on a 10-mark "
                    + "question than the average TA) and %s the most lenient (%s). ", basis,
                    esc(g.labels().get(harsh)), inMarks(g.estimates()[harsh]),
                    esc(g.labels().get(lenient)), inMarks(g.estimates()[lenient])));
        } else {
            int far = Math.abs(g.estimates()[harsh]) >= Math.abs(g.estimates()[lenient]) ? harsh : lenient;
            b.append(String.format(Locale.ROOT, "The TAs did not mark alike%s. The largest difference is %s "
                    + "(%s on a 10-mark question than the average TA in their group). ", basis,
                    esc(g.labels().get(far)), inMarks(g.estimates()[far])));
        }
        in.content().flatMap(ContentResult::causal).ifPresent(f -> b.append(String.format(Locale.ROOT,
                "Of the variation in marks, %.0f%% reflects what students wrote and %.0f%% who marked it. ",
                f.variance().content(), f.variance().assistant())));
        b.append(String.format(Locale.ROOT, "It changes <b>%d</b> letter grade%s. ", o.changed(),
                o.changed() == 1 ? "" : "s"));
        switch (o.shortlist()) {
            case Outcome.Refused r -> b.append("No re-check list: ").append(esc(r.reason()));
            case Outcome.Emitted e when e.rows().isEmpty() ->
                    b.append("No student's grade is likely to depend on it, so nothing needs re-checking.");
            case Outcome.Emitted e -> b.append("<b>Next step:</b> take a second look at the ").append(e.rows().size())
                    .append(e.rows().size() == 1 ? " script" : " scripts")
                    .append(" in the last section, most consequential first.");
        }
        b.append("</div>\n");
    }

    /** Four figures at a glance. */
    private static void glance(StringBuilder b, ReportInput in) {
        List<String[]> tiles = new ArrayList<>();
        in.model().ifPresent(m -> {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double s : m.graders().estimates()) {
                lo = Math.min(lo, s);
                hi = Math.max(hi, s);
            }
            tiles.add(new String[] {String.format(Locale.ROOT, "%.1f", (hi - lo) / 10.0),
                    "marks between the harshest and most lenient TA on a 10-mark question"});
        });
        in.outcome().ifPresent(o -> {
            tiles.add(new String[] {String.valueOf(o.changed()), "letter grades that depend on who marked"});
            tiles.add(new String[] {o.shortlist() instanceof Outcome.Emitted e ? String.valueOf(e.rows().size()) : "0",
                    "scripts recommended for a second look"});
        });
        in.content().flatMap(ContentResult::causal).ifPresent(f -> tiles.add(new String[] {
                String.format(Locale.ROOT, "%.0f%%", f.variance().content()),
                "of the variation in marks explained by what students wrote"}));
        if (tiles.isEmpty()) return;
        b.append("<div class=glance>");
        for (String[] t : tiles) {
            b.append("<div class=tile><div class=big>").append(t[0]).append("</div><div class=small>")
             .append(esc(t[1])).append("</div></div>");
        }
        b.append("</div>\n");
    }

    // ------------------------------------------------------------------ severity

    private static void severityChart(StringBuilder b, FittedModel m) {
        FacetEstimates g = m.graders();
        double range = 2;
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            range = Math.max(range, Math.max(Math.abs(ci[0]), Math.abs(ci[1])));
        }
        range = Math.ceil(range / 2) * 2;
        int rowH = 28, left = 130, width = 440, h = rowH * g.size() + 34;
        b.append("<svg class=chart viewBox='0 0 ").append(left + width + 20).append(' ').append(h)
         .append("' role=img aria-label='TA severity with 95% intervals'>\n");
        double zero = left + width / 2.0;
        b.append(String.format(Locale.ROOT, "<rect x=%d y=2 width=%.1f height=%d class=lenzone />%n", left,
                width / 2.0, h - 26));
        b.append(String.format(Locale.ROOT, "<rect x=%.1f y=2 width=%.1f height=%d class=harshzone />%n", zero,
                width / 2.0, h - 26));
        b.append(String.format(Locale.ROOT, "<line x1=%.1f y1=2 x2=%.1f y2=%d class=axis />%n", zero, zero, h - 24));
        for (int t = (int) -range; t <= range; t += (int) Math.max(2, range / 2)) {
            double x = zero + t * (width / 2.0) / range;
            b.append(String.format(Locale.ROOT, "<text x=%.1f y=%d class=tick>%+d</text>%n", x, h - 8, t));
        }
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            double y = 18 + j * rowH;
            double x0 = zero + ci[0] * (width / 2.0) / range, x1 = zero + ci[1] * (width / 2.0) / range;
            double x = zero + g.estimates()[j] * (width / 2.0) / range;
            b.append(String.format(Locale.ROOT, "<text x=%d y=%.1f class=label>%s</text>%n", left - 10, y + 4,
                    esc(g.labels().get(j))));
            b.append(String.format(Locale.ROOT, "<line x1=%.1f y1=%.1f x2=%.1f y2=%.1f class=ci />%n", x0, y, x1, y));
            b.append(String.format(Locale.ROOT, "<circle cx=%.1f cy=%.1f r=6 class=%s />%n", x, y,
                    g.estimates()[j] >= 0 ? "harsh" : "lenient"));
        }
        b.append("</svg>\n<p class=legend>&larr; more lenient &nbsp;&middot;&nbsp; harsher &rarr;</p>\n");
    }

    private static void severityTable(StringBuilder b, FittedModel m) {
        FacetEstimates g = m.graders();
        b.append("<table><tr><th>TA</th><th>Marks written</th><th>Severity (points)</th>"
                + "<th>95% interval</th><th>On a 10-mark question</th><th>Group</th></tr>\n");
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            boolean clear = ci[0] > 0 || ci[1] < 0;
            b.append(String.format(Locale.ROOT,
                    "<tr><td>%s</td><td>%d</td><td class=num>%s%+.1f%s</td><td class=num>%+.1f to %+.1f</td>"
                    + "<td>%s</td><td>%d</td></tr>%n",
                    esc(g.labels().get(j)), g.counts()[j], clear ? "<b>" : "", g.estimates()[j], clear ? "</b>" : "",
                    ci[0], ci[1], inMarks(g.estimates()[j]), groupOf(m, j)));
        }
        b.append("</table>\n");
        if (m.groups().size() > 1) {
            b.append("<p class=note>TAs in different groups cannot be compared with each other; "
                    + "each group is read against its own average.</p>\n");
        }
    }

    /** The two independent estimates side by side, when both exist. */
    private static void crossCheck(StringBuilder b, ReportInput in, FittedModel primary) {
        Optional<ContentResult> content = in.content().filter(c -> c.status() != ContentResult.Status.NO_TEXT
                && c.status() != ContentResult.Status.NO_LINKS);
        if (content.isEmpty()) return;
        ContentResult c = content.get();
        String otherName;
        double[] other;
        double[][] otherCi;
        if (in.anchoredByAnswers()) {
            if (in.marksOnly().isEmpty()) return;
            FittedModel mo = in.marksOnly().get();
            otherName = "From the marks alone (assumes piles were alike)";
            other = mo.graders().estimates();
            otherCi = new double[other.length][];
            for (int j = 0; j < other.length; j++) otherCi[j] = mo.interval95(j);
        } else {
            otherName = "From matched answers";
            other = c.severity();
            otherCi = new double[other.length][];
            for (int j = 0; j < other.length; j++) otherCi[j] = c.interval95(j);
        }
        b.append("<h3>A second, independent estimate</h3>\n<p>").append(in.anchoredByAnswers()
                ? "The figures above come from the answers. The marks alone give the estimate below, which is only "
                  + "right if every TA's pile was as strong as every other's; where the two disagree, the piles differed."
                : "The figures above come from the marks. Comparing answers that say the same thing gives an "
                  + "independent estimate; agreement between the two is evidence that both are right.")
         .append("</p>\n");
        b.append("<table><tr><th>TA</th><th>Above</th><th>").append(esc(otherName)).append("</th><th>Difference</th></tr>\n");
        double maxDiff = 0;
        for (int j = 0; j < other.length; j++) {
            double d = primary.severity(j) - other[j];
            maxDiff = Math.max(maxDiff, Math.abs(d));
            b.append(String.format(Locale.ROOT, "<tr><td>%s</td><td class=num>%+.1f</td><td class=num>%+.1f "
                    + "<span class=note>(%+.1f to %+.1f)</span></td><td class=num>%+.1f</td></tr>%n",
                    esc(primary.graders().labels().get(j)), primary.severity(j), other[j], otherCi[j][0], otherCi[j][1], d));
        }
        b.append("</table>\n");
        b.append(String.format(Locale.ROOT, "<p class=note>Largest disagreement: %.1f points.</p>%n", maxDiff));
    }

    // ------------------------------------------------------------------ causal

    private static void causal(StringBuilder b, ContentResult c, FittedModel m, String number) {
        CausalFindings f = c.causal().orElseThrow();
        section(b, number + ". Why did the marks differ?");
        b.append("<p>A difference in marks between two TAs' piles has two possible causes: the students in one pile "
                + "wrote better answers (a legitimate difference), or the TA marked the same work differently (a "
                + "marking effect). Even Hand separates them by comparing <b>answers that say the same thing</b>: "
                + "for those, what was written cannot explain a difference in marks, so whatever remains is caused "
                + "by who marked it. The checks below say how far that conclusion can be trusted.</p>\n");
        contentStatus(b, c);

        b.append("<h3>What the variation in marks is made of</h3>\n");
        varianceBar(b, f.variance());

        b.append("<h3>Each TA's difference, split into its causes</h3>\n");
        b.append("<p>Percentage points of each question's maximum, relative to the class on the same questions. "
                + "<i>What students wrote</i> is how much better or worse that pile's answers were; <i>how it was "
                + "marked</i> is the TA's own effect; <i>neither</i> is chance and what the answers' content model "
                + "misses.</p>\n");
        b.append("<table><tr><th>TA</th><th>Answers</th><th>Pile's marks vs class</th><th>= what students wrote</th>"
                + "<th>+ how it was marked</th><th>+ neither</th></tr>\n");
        for (CausalFindings.Decomposition d : f.decomposition()) {
            b.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%d</td><td class=num><b>%+.1f</b></td>"
                    + "<td class=num>%+.1f</td><td class=num>%+.1f</td><td class=num>%+.1f</td></tr>%n",
                    esc(d.assistant()), d.answers(), d.rawGap(), d.contentGap(), d.markingGap(), d.unexplained()));
        }
        b.append("</table>\n");

        b.append("<h3>What kind of difference it is</h3>\n<div class=mech>");
        for (CausalFindings.Mechanism mech : f.mechanisms()) {
            b.append("<div class=mcard><div><span class='badge ").append(badgeClass(mech.kind())).append("'>")
             .append(kindName(mech.kind())).append("</span> <b>").append(esc(mech.assistant())).append("</b></div><p>")
             .append(esc(mech.explanation())).append("</p>");
            if (mech.byQuestion().size() > 1) {
                b.append("<p class=qeff>");
                for (CausalFindings.QuestionEffect e : mech.byQuestion()) {
                    b.append(String.format(Locale.ROOT, "<span>%s <b>%+.1f</b></span> ", esc(e.question()), e.severity()));
                }
                b.append("</p>");
            }
            b.append("</div>");
        }
        b.append("</div>\n");

        b.append("<h3>How sure can we be that the marking caused it?</h3>\n");
        b.append("<table class=checks><tr><th>Check</th><th>What it asks</th><th>Result</th></tr>\n");
        double minShare = 1, worstAfter = 0, worstBefore = 0, worstLength = 0;
        for (CausalFindings.Overlap o : f.overlap()) {
            minShare = Math.min(minShare, o.matchedShare());
            if (Double.isFinite(o.balanceAfter())) worstAfter = Math.max(worstAfter, Math.abs(o.balanceAfter()));
            worstBefore = Math.max(worstBefore, Math.abs(o.balanceBefore()));
            if (Double.isFinite(o.lengthAfter())) worstLength = Math.max(worstLength, Math.abs(o.lengthAfter()));
        }
        check(b, "Comparable answers exist", "Does every TA have answers that closely match answers marked by others?",
                minShare >= 0.5, String.format(Locale.ROOT, "at least %s of each TA's answers have a close match", pct(minShare)));
        check(b, "Like is compared with like", "After matching, are the paired answers equally good by the content model? "
                + "(standardised difference; under 0.1 is balanced)", worstAfter < 0.1,
                String.format(Locale.ROOT, "%.2f after matching, %.2f before", worstAfter, worstBefore));
        check(b, "Negative control: answer length", "A TA cannot change how much a student wrote, so matched pairs should "
                + "not differ in length either", worstLength < 0.2, String.format(Locale.ROOT, "%.2f", worstLength));
        f.doubleMarked().ifPresent(dm -> check(b, "Same script, two TAs",
                "Scripts marked twice compare TAs on identical content: do they agree with the matched-answer estimate?",
                !Double.isFinite(dm.agreement()) || dm.agreement() >= 0.7,
                String.format(Locale.ROOT, "%d scripts; correlation with the matched estimate %s", dm.scripts(),
                        Double.isFinite(dm.agreement()) ? String.format(Locale.ROOT, "%.2f", dm.agreement()) : "n/a")));
        boolean real = m.noEffect().rejectsAt(0.05);
        check(b, "Placebo", "With TA labels shuffled among answers of similar quality, does the method still find "
                + "differences this large?", !real || f.placebo().pValue() < 0.05,
                String.format(Locale.ROOT, "p = %.3f (spread %.1f observed, %.1f under shuffling)", f.placebo().pValue(),
                        f.placebo().observedSpread(), f.placebo().meanSpreadUnderShuffle()));
        b.append("</table>\n");

        if (!f.sensitivity().isEmpty()) {
            b.append("<p><b>Sensitivity to what the answers' embedding might miss.</b> The conclusion that a TA is "
                    + "harsher or more lenient assumes matched answers really were equally good. &Gamma; is how strongly "
                    + "a hidden quality difference would have to favour one side of every pair to overturn it: "
                    + "&Gamma; = 1 means the evidence is not conclusive even without hidden bias; 2 or more is robust.</p>\n");
            b.append("<table><tr><th>TA</th><th>Matched pairs</th><th>Direction</th><th>p (no hidden bias)</th>"
                    + "<th>Robust up to &Gamma;</th></tr>\n");
            for (CausalFindings.Sensitivity s : f.sensitivity()) {
                b.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%d</td><td>%s</td><td class=num>%s</td>"
                        + "<td class=num>%s</td></tr>%n", esc(s.assistant()), s.pairs(),
                        s.direction() > 0 ? "harsher" : "more lenient", pValue(s.pAtOne()),
                        s.gamma() <= 1.0 ? "not conclusive" : String.format(Locale.ROOT, "%.1f", s.gamma())));
            }
            b.append("</table>\n");
        }
    }

    private static void check(StringBuilder b, String name, String question, boolean ok, String result) {
        b.append("<tr><td><span class='dot ").append(ok ? "ok" : "warn").append("'></span>").append(esc(name))
         .append("</td><td>").append(esc(question)).append("</td><td>").append(esc(result)).append("</td></tr>\n");
    }

    private static void varianceBar(StringBuilder b, CausalFindings.VarianceShare v) {
        b.append("<div class=vbar role=img aria-label='Shares of the variation in marks'>")
         .append(String.format(Locale.ROOT, "<span class=vc style='width:%.1f%%'></span>", v.content()))
         .append(String.format(Locale.ROOT, "<span class=va style='width:%.1f%%'></span>", v.assistant()))
         .append(String.format(Locale.ROOT, "<span class=vr style='width:%.1f%%'></span>", v.residual()))
         .append("</div>\n<p class=vlegend>")
         .append(String.format(Locale.ROOT, "<span class='key vc'></span>What students wrote <b>%.0f%%</b> &nbsp; "
                 + "<span class='key va'></span>Who marked it <b>%.0f%%</b> &nbsp; "
                 + "<span class='key vr'></span>Neither (chance, rubric ambiguity) <b>%.0f%%</b>",
                 v.content(), v.assistant(), v.residual()))
         .append("</p>\n");
    }

    private static void contentStatus(StringBuilder b, ContentResult c) {
        b.append("<p class=note>Answers read by ").append(esc(c.embedder())).append(String.format(Locale.ROOT,
                ". %s of responses carry text; what an answer says explains %.0f%% of how its mark varies within a "
                + "TA's pile; %s of answers have a close match marked by another TA.", pct(c.textCoverage()),
                100 * c.signal(), pct(c.matchedShare()))).append("</p>\n");
        for (String n : c.notes()) b.append("<p class=note>").append(esc(n)).append("</p>\n");
    }

    // ------------------------------------------------------------------ evidence

    private static void evidence(StringBuilder b, ContentResult c, String number) {
        boolean any = !c.sameAnswerDifferentMarks().isEmpty() || !c.sameAssistantInconsistent().isEmpty()
                || !c.outOfLine().isEmpty();
        section(b, number + ". What the answers show");
        if (!any) {
            b.append("<p>No pair of near-identical answers received clearly different marks, and no mark is far out "
                    + "of line with similar answers.</p>\n");
            return;
        }
        if (!c.sameAnswerDifferentMarks().isEmpty()) {
            b.append("<h3>Nearly the same answer, different marks, different TAs</h3>\n");
            pairs(b, c.sameAnswerDifferentMarks());
        }
        if (!c.sameAssistantInconsistent().isEmpty()) {
            b.append("<h3>Nearly the same answer, different marks, same TA</h3>\n<p class=note>Not severity, which "
                    + "would affect both alike, but inconsistency: worth discussing the rubric with the TA.</p>\n");
            pairs(b, c.sameAssistantInconsistent());
        }
        if (!c.outOfLine().isEmpty()) {
            b.append("<h3>Marks out of line with similar answers</h3>\n<p class=note>Each mark is far from what the "
                    + "most similar answers received once each TA's severity is allowed for: possibly a slip, a page "
                    + "missed, or an answer the content model misreads. Check the script; do not correct blindly.</p>\n");
            b.append("<table><tr><th>Question</th><th>Student</th><th>TA</th><th>Mark</th><th>Similar answers got</th>"
                    + "<th>Answer</th></tr>\n");
            for (ContentResult.OutOfLine o : c.outOfLine()) {
                b.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%s</td><td class=num>%s / %s</td>"
                        + "<td class=num>about %s</td><td class=excerpt>%s</td></tr>%n", esc(o.question()),
                        esc(o.student()), esc(o.assistant()), marks(o.marks()), marks(o.maxMarks()),
                        marks(Math.round(o.expectedMarks() * 2) / 2.0), esc(o.excerpt())));
            }
            b.append("</table>\n");
        }
    }

    private static void pairs(StringBuilder b, List<ContentResult.PairEvidence> list) {
        b.append("<div class=pairs>");
        for (ContentResult.PairEvidence p : list) {
            b.append("<div class=pair><div class=phead>").append(esc(p.question()))
             .append(String.format(Locale.ROOT, " &middot; %.0f%% alike &middot; %s marks apart</div>",
                     100 * p.similarity(), marks(p.gapPoints() * p.maxMarks() / 100.0)));
            side(b, p.first(), p.maxMarks(), "hi");
            side(b, p.second(), p.maxMarks(), "lo");
            b.append("</div>");
        }
        b.append("</div>\n");
    }

    private static void side(StringBuilder b, ContentResult.Side s, double max, String cls) {
        b.append("<div class='side ").append(cls).append("'><div class=who><b>").append(marks(s.marks())).append(" / ")
         .append(marks(max)).append("</b> &middot; ").append(esc(s.student())).append(" &middot; marked by ")
         .append(esc(s.assistant())).append("</div><div class=excerpt>").append(esc(s.excerpt())).append("</div></div>");
    }

    // ------------------------------------------------------------------ grades

    private static void gradeTable(StringBuilder b, Outcome.Result o) {
        b.append("<table><tr><th></th>");
        for (LetterGrade g : LetterGrade.awardable()) b.append("<th>").append(g).append("</th>");
        b.append("</tr>\n<tr><td>As marked</td>");
        for (LetterGrade g : LetterGrade.awardable()) b.append("<td>").append(count(o.gradesAsMarked(), g)).append("</td>");
        b.append("</tr>\n<tr><td>Corrected</td>");
        for (LetterGrade g : LetterGrade.awardable()) b.append("<td>").append(count(o.gradesCorrected(), g)).append("</td>");
        b.append("</tr></table>\n");
    }

    private static void shortlistTable(StringBuilder b, Outcome.Emitted e) {
        if (e.rows().isEmpty()) {
            b.append("<p class=note>The TAs differ, but no student's letter grade is likely to depend on it.</p>\n");
            return;
        }
        b.append("<p>Ranked by expected change in grade points (R.21.1 points are uneven: D&rarr;E costs two). "
                + "Saved copies name students by pseudonym; roll numbers appear only on your screen, and the "
                + "sealed key file maps them back.</p>\n");
        b.append("<table><tr><th>Student</th><th>Total as marked</th><th>Corrected</th><th>Grade</th>"
                + "<th>If it changes</th><th>Chance of change</th><th>Direction</th></tr>\n");
        for (Outcome.Row r : e.rows()) {
            b.append(String.format(Locale.ROOT,
                    "<tr><td>%s</td><td class=num>%.1f</td><td class=num>%.1f</td><td>%s</td><td>%s</td>"
                    + "<td class=num>%.0f%%</td><td>%s</td></tr>%n",
                    esc(r.student()), r.totalAsMarked(), r.totalCorrected(), r.gradeAsMarked(), r.likelyGrade(),
                    100 * r.probability(), r.expectedPoints() > 0 ? "probably marked down" : "probably marked up"));
        }
        b.append("</table>\n");
        b.append(String.format(Locale.ROOT, "<p class=note>Expect about %.1f of these %d to turn out unchanged on "
                + "re-marking. That is the price of catching the others.</p>%n", e.expectedFalseFlags(), e.rows().size()));
    }

    // ------------------------------------------------------------------ frame

    /** Findings grouped by kind: twenty notes that say the same thing are one line and a count. */
    private static void diagnostics(StringBuilder b, ReportInput in) {
        if (in.diagnostics().isEmpty()) return;
        Map<Diagnostic.Code, List<Diagnostic>> byCode = new LinkedHashMap<>();
        for (Diagnostic d : in.diagnostics()) byCode.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d);
        b.append("<details><summary>").append(in.diagnostics().size()).append(" finding")
         .append(in.diagnostics().size() == 1 ? "" : "s").append(" from reading the file and auditing the design</summary><ul>\n");
        for (List<Diagnostic> group : byCode.values()) {
            b.append("<li><code>").append(esc(group.get(0).render())).append("</code>");
            if (group.size() > 1) b.append(" <span class=note>and ").append(group.size() - 1).append(" more like it</span>");
            b.append("</li>\n");
        }
        b.append("</ul></details>\n");
    }

    private static void footer(StringBuilder b, ReportInput in) {
        b.append("<footer><p>Inputs and how to reproduce this report:</p><ul>");
        for (String f : in.footnotes()) b.append("<li>").append(esc(f)).append("</li>");
        b.append("</ul><p>No grade has been changed by this tool. Severity describes a marking session, not a "
                + "person. Generated locally; nothing left this machine.</p></footer>\n</main></body></html>\n");
    }

    private static void head(StringBuilder b, String title) {
        b.append("<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>")
         .append(esc(title)).append("</title><style>\n").append(CSS).append("</style></head><body><main>\n");
    }

    private static void section(StringBuilder b, String title) {
        b.append("<h2>").append(esc(title)).append("</h2>\n");
    }

    private static String kindName(CausalFindings.Kind k) {
        return switch (k) {
            case CONSISTENT_SEVERITY -> "Consistent severity";
            case QUESTION_SPECIFIC -> "Rubric difference";
            case DRIFT -> "Drift";
            case ISOLATED_SLIPS -> "Isolated slips";
            case NO_CLEAR_DIFFERENCE -> "No clear difference";
        };
    }

    private static String badgeClass(CausalFindings.Kind k) {
        return switch (k) {
            case CONSISTENT_SEVERITY, QUESTION_SPECIFIC -> "b-red";
            case DRIFT, ISOLATED_SLIPS -> "b-amber";
            case NO_CLEAR_DIFFERENCE -> "b-green";
        };
    }

    private static String inMarks(double points) {
        double marks = points / 10.0;
        if (Math.abs(marks) < 0.05) return "about the same";
        return String.format(Locale.ROOT, "%.1f mark%s %s", Math.abs(marks), Math.abs(marks) == 1.0 ? "" : "s",
                marks > 0 ? "less" : "more");
    }

    private static int groupOf(FittedModel m, int j) {
        for (int k = 0; k < m.groups().size(); k++) if (m.groups().get(k).contains(j)) return k + 1;
        return 0;
    }

    private static int count(LetterGrade[] grades, LetterGrade g) {
        int c = 0;
        for (LetterGrade x : grades) if (x == g) c++;
        return c;
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * share);
    }

    private static String pValue(double p) {
        return p < 0.001 ? "&lt; 0.001" : String.format(Locale.ROOT, "%.3f", p);
    }

    private static String marks(double m) {
        return m == Math.rint(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#f7f6f2;--fg:#1b2230;--muted:#5d6678;--line:#dcdfe6;--accent:#7a1f2b;--harsh:#b4442f;
            --lenient:#2f6fb4;--card:#fff;--ok:#1a7f37;--warn:#b26b00;--vc:#3f7d5a;--va:#b4442f;--vr:#b9bec9;
            --hi:#e9f3ec;--lo:#fbeceb}
            @media (prefers-color-scheme:dark){:root{--bg:#12151b;--fg:#e6e8ec;--muted:#a3abba;--line:#2f3542;
            --accent:#e08a7a;--harsh:#e5826f;--lenient:#7fb0e8;--card:#1a1e26;--ok:#57c27a;--warn:#e0a84a;
            --vc:#5fae82;--va:#e5826f;--vr:#4b5160;--hi:#18291f;--lo:#2e1a1a}}
            *{box-sizing:border-box}
            body{background:var(--bg);color:var(--fg);font:15px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0}
            main{max-width:960px;margin:0 auto;padding:20px 16px 56px}
            .top{display:flex;gap:10px;align-items:baseline;border-bottom:2px solid var(--accent);padding-bottom:6px}
            .brand{font-weight:800;letter-spacing:.02em;color:var(--accent);font-size:18px}.kind{color:var(--muted)}
            h1{font-size:26px;margin:14px 0 2px}h2{font-size:19px;margin:34px 0 10px;padding-bottom:4px;border-bottom:1px solid var(--line)}
            h3{font-size:15.5px;margin:22px 0 6px}
            .meta{color:var(--muted);margin:0 0 12px}.verdict{font-weight:600;color:var(--accent)}
            .bottom{background:var(--card);border:1px solid var(--line);border-left:5px solid var(--accent);border-radius:8px;padding:14px 16px;margin:14px 0}
            .glance{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin:12px 0 4px}
            .tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px}
            .tile .big{font-size:26px;font-weight:700;font-variant-numeric:tabular-nums}.tile .small{color:var(--muted);font-size:13px;line-height:1.35}
            .note{color:var(--muted)}table{border-collapse:collapse;width:100%;margin:8px 0;background:var(--card);font-size:14px}
            th,td{border:1px solid var(--line);padding:6px 9px;text-align:left;vertical-align:top}
            th{color:var(--muted);font-weight:600;background:transparent}td.num{font-variant-numeric:tabular-nums;white-space:nowrap}
            .chart{width:100%;max-width:620px;height:auto;display:block}.axis{stroke:var(--muted);stroke-dasharray:3 3}
            .lenzone{fill:var(--lenient);opacity:.05}.harshzone{fill:var(--harsh);opacity:.05}
            .ci{stroke:var(--muted);stroke-width:2.5;stroke-linecap:round}.harsh{fill:var(--harsh)}.lenient{fill:var(--lenient)}
            .label{fill:var(--fg);font-size:12px;text-anchor:end}.tick{fill:var(--muted);font-size:11px;text-anchor:middle}
            .legend{color:var(--muted);font-size:13px;margin-top:0}code{white-space:pre-wrap;font-size:12.5px}
            .vbar{display:flex;height:22px;border-radius:6px;overflow:hidden;border:1px solid var(--line);margin:6px 0}
            .vbar span{display:block;height:100%}.vc{background:var(--vc)}.va{background:var(--va)}.vr{background:var(--vr)}
            .vlegend{font-size:13.5px;color:var(--muted)}.key{display:inline-block;width:11px;height:11px;border-radius:3px;margin-right:5px;vertical-align:-1px}
            .mech{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:10px}
            .mcard{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 12px}.mcard p{margin:6px 0 0}
            .qeff span{display:inline-block;margin:2px 8px 0 0;font-size:13px;color:var(--muted)}
            .badge{font-size:11.5px;font-weight:700;padding:2px 8px;border-radius:999px;text-transform:uppercase;letter-spacing:.03em}
            .b-red{background:rgba(180,68,47,.14);color:var(--harsh)}.b-amber{background:rgba(178,107,0,.14);color:var(--warn)}
            .b-green{background:rgba(26,127,55,.14);color:var(--ok)}
            .checks td:first-child{white-space:nowrap}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:7px}
            .dot.ok{background:var(--ok)}.dot.warn{background:var(--warn)}
            .pairs{display:grid;gap:10px}.pair{background:var(--card);border:1px solid var(--line);border-radius:8px;overflow:hidden}
            .phead{padding:6px 10px;font-size:13px;color:var(--muted);border-bottom:1px solid var(--line)}
            .side{padding:8px 10px}.side.hi{background:var(--hi)}.side.lo{background:var(--lo)}.who{font-size:13px;margin-bottom:3px}
            .excerpt{font-size:13.5px;white-space:pre-wrap}td.excerpt{max-width:340px}
            details{margin:8px 0}summary{cursor:pointer;color:var(--muted)}
            footer{margin-top:36px;color:var(--muted);font-size:12.5px;border-top:1px solid var(--line)}
            @media print{body{background:#fff;color:#000}main{max-width:none}.bottom,.tile,.mcard,.pair{break-inside:avoid}h2{break-after:avoid}}
            """;
}
