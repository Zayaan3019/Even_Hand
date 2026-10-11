package in.ac.iitm.evenhand.report;

import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.outcome.Outcome;

import java.util.Locale;

/**
 * Stage 5: the reports, as single self-contained HTML files that open in any browser
 * with no network access.
 *
 * <ul>
 *   <li>{@link #instructor} is for the instructor alone. It names assistants by
 *       pseudonym, with an interval on every figure, and lists the scripts worth a
 *       second look.</li>
 *   <li>{@link #committee} is for the Class Committee. It carries aggregates only: no
 *       assistant and no student appears in it.</li>
 * </ul>
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String instructor(ReportInput in) {
        StringBuilder b = new StringBuilder();
        head(b, "Even Hand: " + in.courseLabel());
        b.append("<h1>Even Hand <span class=sub>grading-consistency report</span></h1>\n");
        b.append("<p class=meta>").append(esc(in.courseLabel())).append(" &middot; ")
         .append(in.design().studentCount()).append(" students &middot; ")
         .append(in.design().graderCount()).append(" assistants &middot; ")
         .append(in.design().questionCount()).append(" questions &middot; for the instructor only</p>\n");
        bottomLine(b, in);

        section(b, "1. Can this marking arrangement be checked?");
        b.append("<p class=verdict>").append(esc(in.verdict().headline())).append("</p>\n");
        b.append("<p>").append(esc(in.verdict().exposure().headline())).append("</p>\n");
        diagnostics(b, in);

        if (in.model().isEmpty()) {
            b.append("<p class=note>Severity was not estimated: the arrangement cannot support it. "
                    + "Nothing below this line is computed, by design.</p>\n");
            footer(b, in);
            return b.toString();
        }
        FittedModel m = in.model().get();
        Outcome.Result o = in.outcome().orElseThrow();

        section(b, "2. How differently did the assistants mark?");
        b.append("<p>Positive means harsher than the panel average, negative more lenient, in "
                + "<b>percentage points of each question's maximum</b>: +5 means about half a mark less "
                + "on a 10-mark question. Bars are 95% intervals from resampling the students.</p>\n");
        severityChart(b, m);
        severityTable(b, m);
        b.append(String.format(Locale.ROOT,
                "<p>No-effect test: in %d simulated courses with the same allocation and identical assistants, "
                + "a spread of %.1f points or more arose with probability <b>p = %.3f</b>. %s</p>\n",
                m.noEffect().simulations(), m.noEffect().observedSpread(), m.noEffect().pValue(),
                m.noEffect().rejectsAt(0.05) ? "The differences are unlikely to be chance."
                        : "The differences are within what chance alone produces."));

        section(b, "3. What did it do to grades?");
        b.append(String.format(Locale.ROOT,
                "<p><b>%.1f%%</b> of the spread in course totals came from who marked the script rather than "
                + "what was written. Correcting for it would change <b>%d</b> letter grade%s "
                + "(90%% range %d&ndash;%d).</p>\n",
                o.assistantShare(), o.changed(), o.changed() == 1 ? "" : "s", o.changedLow(), o.changedHigh()));
        gradeTable(b, o);

        section(b, "4. Scripts worth a second look");
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
        b.append("<h1>Class Committee sheet <span class=sub>aggregate only</span></h1>\n");
        b.append("<p class=meta>").append(esc(in.courseLabel())).append(" &middot; ")
         .append(in.design().studentCount()).append(" students &middot; ")
         .append(in.design().graderCount()).append(" assistants</p>\n");
        b.append("<p class=verdict>").append(esc(in.verdict().headline())).append("</p>\n");
        b.append("<p>").append(esc(in.verdict().exposure().headline())).append("</p>\n");
        in.outcome().ifPresent(o -> {
            b.append(String.format(Locale.ROOT,
                    "<p><b>%.1f%%</b> of the spread in totals is attributable to the division of marking. "
                    + "Letter grades that would change on correction: <b>%d</b> (90%% range %d&ndash;%d).</p>\n",
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

    /** The whole report in three sentences, with the one thing to do next. */
    static void bottomLine(StringBuilder b, ReportInput in) {
        b.append("<div class=bottom><b>Bottom line.</b> ");
        if (in.model().isEmpty()) {
            b.append("This way of dividing the marking cannot tell a harsh TA from a weaker group of students, "
                    + "so no TA is rated and no grade is questioned. Section 1 says why, and what would make "
                    + "it checkable next time.</div>\n");
            return;
        }
        FittedModel m = in.model().get();
        Outcome.Result o = in.outcome().orElseThrow();
        if (!m.noEffect().rejectsAt(0.05)) {
            b.append(String.format(Locale.ROOT, "The TAs marked consistently: their differences are within what "
                    + "chance alone produces (p = %.2f). No script needs a second look on these grounds.</div>%n",
                    m.noEffect().pValue()));
            return;
        }
        FacetEstimates g = m.graders();
        int harsh = 0, lenient = 0;
        for (int j = 1; j < g.size(); j++) {
            if (g.estimates()[j] > g.estimates()[harsh]) harsh = j;
            if (g.estimates()[j] < g.estimates()[lenient]) lenient = j;
        }
        if (m.groups().size() == 1) {
            b.append(String.format(Locale.ROOT, "The TAs did not mark alike. %s was the harshest (%s on a 10-mark "
                    + "question than the average TA) and %s the most lenient (%s). ",
                    esc(g.labels().get(harsh)), inMarks(g.estimates()[harsh]),
                    esc(g.labels().get(lenient)), inMarks(g.estimates()[lenient])));
        } else {
            int far = Math.abs(g.estimates()[harsh]) >= Math.abs(g.estimates()[lenient]) ? harsh : lenient;
            b.append(String.format(Locale.ROOT, "The TAs did not mark alike. The largest difference is %s "
                    + "(%s on a 10-mark question than the average TA in their group). ",
                    esc(g.labels().get(far)), inMarks(g.estimates()[far])));
        }
        b.append(String.format(Locale.ROOT, "It changes <b>%d</b> letter grade%s. ", o.changed(),
                o.changed() == 1 ? "" : "s"));
        switch (o.shortlist()) {
            case Outcome.Refused r -> b.append("No re-check list: ").append(esc(r.reason()));
            case Outcome.Emitted e when e.rows().isEmpty() ->
                    b.append("No student's grade is likely to depend on it, so nothing needs re-checking.");
            case Outcome.Emitted e -> b.append("<b>Next step:</b> take a second look at the ").append(e.rows().size())
                    .append(e.rows().size() == 1 ? " script" : " scripts")
                    .append(" in section 4, most consequential first.");
        }
        b.append("</div>\n");
    }

    private static void severityChart(StringBuilder b, FittedModel m) {
        FacetEstimates g = m.graders();
        double range = 2;
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            range = Math.max(range, Math.max(Math.abs(ci[0]), Math.abs(ci[1])));
        }
        range = Math.ceil(range / 2) * 2;
        int rowH = 26, left = 110, width = 420, h = rowH * g.size() + 30;
        b.append("<svg class=chart viewBox='0 0 ").append(left + width + 20).append(' ').append(h)
         .append("' role=img aria-label='Assistant severity with 95% intervals'>\n");
        double zero = left + width / 2.0;
        b.append(String.format(Locale.ROOT, "<line x1=%.1f y1=4 x2=%.1f y2=%d class=axis />%n", zero, zero, h - 22));
        for (int t = (int) -range; t <= range; t += (int) Math.max(2, range / 2)) {
            double x = zero + t * (width / 2.0) / range;
            b.append(String.format(Locale.ROOT, "<text x=%.1f y=%d class=tick>%+d</text>%n", x, h - 6, t));
        }
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            double y = 16 + j * rowH;
            double x0 = zero + ci[0] * (width / 2.0) / range, x1 = zero + ci[1] * (width / 2.0) / range;
            double x = zero + g.estimates()[j] * (width / 2.0) / range;
            b.append(String.format(Locale.ROOT, "<text x=%d y=%.1f class=label>%s</text>%n", left - 8, y + 4,
                    esc(g.labels().get(j))));
            b.append(String.format(Locale.ROOT, "<line x1=%.1f y1=%.1f x2=%.1f y2=%.1f class=ci />%n", x0, y, x1, y));
            b.append(String.format(Locale.ROOT, "<circle cx=%.1f cy=%.1f r=5 class=%s />%n", x, y,
                    g.estimates()[j] >= 0 ? "harsh" : "lenient"));
        }
        b.append("</svg>\n<p class=legend>&larr; more lenient &nbsp;&middot;&nbsp; harsher &rarr;</p>\n");
    }

    private static void severityTable(StringBuilder b, FittedModel m) {
        FacetEstimates g = m.graders();
        b.append("<table><tr><th>Assistant</th><th>Marks written</th><th>Severity (points)</th>"
                + "<th>95% interval</th><th>On a 10-mark question</th><th>Group</th></tr>\n");
        for (int j = 0; j < g.size(); j++) {
            double[] ci = m.interval95(j);
            b.append(String.format(Locale.ROOT,
                    "<tr><td>%s</td><td>%d</td><td>%+.1f</td><td>%+.1f to %+.1f</td><td>%s</td><td>%d</td></tr>%n",
                    esc(g.labels().get(j)), g.counts()[j], g.estimates()[j], ci[0], ci[1],
                    inMarks(g.estimates()[j]), groupOf(m, j)));
        }
        b.append("</table>\n");
        if (m.groups().size() > 1) {
            b.append("<p class=note>Assistants in different groups cannot be compared with each other; "
                    + "each group is read against its own average.</p>\n");
        }
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
            b.append("<p class=note>The assistants differ, but no student's letter grade is likely to depend on it.</p>\n");
            return;
        }
        b.append("<p>Ranked by expected change in grade points (R.21.1 points are uneven: D&rarr;E costs two). "
                + "Saved copies name students by pseudonym; roll numbers appear only on your screen, and the "
                + "sealed key file maps them back.</p>\n");
        b.append("<table><tr><th>Student</th><th>Total as marked</th><th>Corrected</th><th>Grade</th>"
                + "<th>If it changes</th><th>Chance of change</th><th>Direction</th></tr>\n");
        for (Outcome.Row r : e.rows()) {
            b.append(String.format(Locale.ROOT,
                    "<tr><td>%s</td><td>%.1f</td><td>%.1f</td><td>%s</td><td>%s</td><td>%.0f%%</td><td>%s</td></tr>%n",
                    esc(r.student()), r.totalAsMarked(), r.totalCorrected(), r.gradeAsMarked(), r.likelyGrade(),
                    100 * r.probability(), r.expectedPoints() > 0 ? "probably marked down" : "probably marked up"));
        }
        b.append("</table>\n");
        b.append(String.format(Locale.ROOT, "<p class=note>Expect about %.1f of these %d to turn out unchanged on "
                + "re-marking. That is the price of catching the others.</p>\n", e.expectedFalseFlags(), e.rows().size()));
    }

    /** Findings grouped by kind: twenty notes that say the same thing are one line and a count. */
    private static void diagnostics(StringBuilder b, ReportInput in) {
        if (in.diagnostics().isEmpty()) return;
        java.util.Map<Diagnostic.Code, java.util.List<Diagnostic>> byCode = new java.util.LinkedHashMap<>();
        for (Diagnostic d : in.diagnostics()) byCode.computeIfAbsent(d.code(), k -> new java.util.ArrayList<>()).add(d);
        b.append("<details><summary>").append(in.diagnostics().size()).append(" finding")
         .append(in.diagnostics().size() == 1 ? "" : "s").append(" from reading the file and auditing the design</summary><ul>\n");
        for (java.util.List<Diagnostic> group : byCode.values()) {
            b.append("<li><code>").append(esc(group.get(0).render())).append("</code>");
            if (group.size() > 1) b.append(" <span class=note>and ").append(group.size() - 1).append(" more like it</span>");
            b.append("</li>\n");
        }
        b.append("</ul></details>\n");
    }

    private static void footer(StringBuilder b, ReportInput in) {
        b.append("<footer><p>Inputs:</p><ul>");
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

    private static int count(LetterGrade[] grades, LetterGrade g) {
        int c = 0;
        for (LetterGrade x : grades) if (x == g) c++;
        return c;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#fbfaf7;--fg:#1d2433;--muted:#5d6678;--line:#d9dce3;--accent:#8a1c1c;--harsh:#b4442f;--lenient:#2f6fb4;--card:#fff}
            @media (prefers-color-scheme:dark){:root{--bg:#14171d;--fg:#e6e8ec;--muted:#a3abba;--line:#323844;--accent:#e08a7a;--harsh:#e5826f;--lenient:#7fb0e8;--card:#1b1f27}}
            body{background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;margin:0}
            main{max-width:860px;margin:0 auto;padding:24px 16px 48px}
            h1{font-size:26px;margin:0 0 4px}h1 .sub{font-weight:400;color:var(--muted);font-size:17px}
            h2{font-size:18px;margin:28px 0 8px;border-bottom:1px solid var(--line);padding-bottom:4px}
            .meta{color:var(--muted);margin:0 0 12px}.verdict{font-weight:600;color:var(--accent)}
            .bottom{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--accent);border-radius:6px;padding:12px 14px;margin:14px 0 4px}
            .note{color:var(--muted)}table{border-collapse:collapse;width:100%;margin:8px 0;background:var(--card)}
            th,td{border:1px solid var(--line);padding:5px 8px;text-align:left;font-variant-numeric:tabular-nums}
            th{background:transparent;color:var(--muted);font-weight:600}
            .chart{width:100%;max-width:560px;height:auto;display:block}.axis{stroke:var(--muted);stroke-dasharray:3 3}
            .ci{stroke:var(--muted);stroke-width:2}.harsh{fill:var(--harsh)}.lenient{fill:var(--lenient)}
            .label{fill:var(--fg);font-size:12px;text-anchor:end}.tick{fill:var(--muted);font-size:11px;text-anchor:middle}
            .legend{color:var(--muted);font-size:13px;margin-top:0}code{white-space:pre-wrap;font-size:12.5px}
            footer{margin-top:32px;color:var(--muted);font-size:13px;border-top:1px solid var(--line)}
            """;
}
