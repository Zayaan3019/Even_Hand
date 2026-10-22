package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.content.CausalFindings;
import in.ac.iitm.evenhand.content.ContentFit;
import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.ingest.PseudonymKey;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.HtmlReport;
import in.ac.iitm.evenhand.report.ReportInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A run as structured data, for the web app to render: every figure the HTML report shows, with
 * pseudonyms, plus the two self-contained HTML reports for saving and printing, plus the
 * pseudonym-to-name table that only this computer's browser ever receives.
 *
 * <p>Like the report, it adds no numbers of its own. Field names are part of the contract with
 * {@code eh-web/src/api.ts}.
 */
final class ResultJson {

    private ResultJson() {
    }

    static String of(Pipeline.Run run) {
        ReportInput in = run.report();
        Json j = new Json();
        j.raw("course", new Json()
                .str("name", in.courseLabel())
                .num("students", in.design().studentCount())
                .num("tas", in.design().graderCount())
                .num("questions", in.design().questionCount())
                .num("answers", run.ingest().answerCount())
                .num("keys", run.ingest().references().size())
                .toString());
        j.raw("verdict", verdict(in, run));
        j.str("basis", in.anchoredByAnswers() ? "answers" : in.model().isPresent() ? "marks" : "none");
        j.str("choice", in.choiceNote());
        boolean confirmed = run.content().map(c -> {
            ContentFit.Choice ch =
                    ContentFit.choose(run.markingVerdict(), run.marksModel(), c);
            return !ch.useAnswers() && Double.isFinite(ch.pValue());
        }).orElse(false);
        j.bool("confirmedByAnswers", confirmed);
        j.str("bottomLineHtml", HtmlReport.bottomLineHtml(in));
        in.model().ifPresent(m -> {
            j.raw("tas", tas(m));
            j.raw("noEffect", new Json().num("p", m.noEffect().pValue()).num("simulations", m.noEffect().simulations())
                    .num("spread", m.noEffect().observedSpread()).toString());
        });
        crossCheck(in).ifPresent(cc -> j.raw("crossCheck", cc));
        run.content().ifPresent(c -> j.raw("content", content(c)));
        run.outcome().ifPresent(o -> j.raw("outcome", outcome(o)));
        j.raw("diagnostics", diagnostics(in.diagnostics()));
        List<String> notes = new ArrayList<>();
        for (String f : in.footnotes()) notes.add(Json.quote(f));
        j.raw("footnotes", Json.array(notes));
        Json manifest = new Json();
        run.manifest().forEach(manifest::str);
        j.raw("manifest", manifest.toString());
        j.str("instructorHtml", HtmlReport.instructor(in));
        j.str("committeeHtml", HtmlReport.committee(in));
        Json names = new Json();
        PseudonymKey.of(run.ingest().keyFileContents()).realByLabel().forEach(names::str);
        j.raw("names", names.toString());
        return j.toString();
    }

    private static String verdict(ReportInput in, Pipeline.Run run) {
        AuditVerdict v = in.verdict();
        String kind = switch (v) {
            case AuditVerdict.Estimable e -> "estimable";
            case AuditVerdict.EstimableUnderExchangeability e -> "under-exchangeability";
            case AuditVerdict.PartiallyEstimable e -> "partial";
            case AuditVerdict.NotEstimable e -> "not-estimable";
            case AuditVerdict.EstimableFromContent e -> "from-answers";
        };
        return new Json().str("kind", kind).str("headline", v.headline())
                .str("exposure", v.exposure().headline()).str("exposureStatus", v.exposure().status().name())
                .str("fromMarking", run.markingVerdict().headline()).toString();
    }

    private static String tas(FittedModel m) {
        FacetEstimates g = m.graders();
        List<String> rows = new ArrayList<>();
        for (int k = 0; k < g.size(); k++) {
            double[] ci = m.interval95(k);
            int group = 0;
            for (int x = 0; x < m.groups().size(); x++) if (m.groups().get(x).contains(k)) group = x + 1;
            rows.add(new Json().str("label", g.labels().get(k)).num("marks", g.counts()[k])
                    .num("severity", g.estimates()[k]).num("lo", ci[0]).num("hi", ci[1]).num("se", g.standardErrors()[k])
                    .num("group", group).bool("clear", ci[0] > 0 || ci[1] < 0).toString());
        }
        return Json.array(rows);
    }

    private static java.util.Optional<String> crossCheck(ReportInput in) {
        if (in.model().isEmpty() || in.content().isEmpty()) return java.util.Optional.empty();
        ContentResult c = in.content().get();
        if (c.status() == ContentResult.Status.NO_TEXT || c.status() == ContentResult.Status.NO_LINKS) {
            return java.util.Optional.empty();
        }
        FittedModel m = in.model().get();
        List<String> rows = new ArrayList<>();
        String otherName;
        if (in.anchoredByAnswers()) {
            if (in.marksOnly().isEmpty()) return java.util.Optional.empty();
            FittedModel mo = in.marksOnly().get();
            otherName = "From the marks alone (assumes piles were alike)";
            for (int k = 0; k < m.graders().size(); k++) {
                double[] ci = mo.interval95(k);
                rows.add(new Json().str("label", m.graders().labels().get(k)).num("used", m.severity(k))
                        .num("other", mo.severity(k)).num("lo", ci[0]).num("hi", ci[1]).toString());
            }
        } else {
            otherName = "From matched answers";
            for (int k = 0; k < m.graders().size(); k++) {
                double[] ci = c.interval95(k);
                rows.add(new Json().str("label", m.graders().labels().get(k)).num("used", m.severity(k))
                        .num("other", c.severity()[k]).num("lo", ci[0]).num("hi", ci[1]).toString());
            }
        }
        return java.util.Optional.of(new Json().str("otherName", otherName).raw("rows", Json.array(rows)).toString());
    }

    private static String content(ContentResult c) {
        Json j = new Json().str("status", c.status().name()).str("embedder", c.embedder()).bool("semantic", c.semantic())
                .num("textCoverage", c.textCoverage()).num("signal", c.signal()).num("matchedShare", c.matchedShare())
                .num("p", c.pValue());
        List<String> notes = new ArrayList<>();
        for (String n : c.notes()) notes.add(Json.quote(n));
        j.raw("notes", Json.array(notes));
        List<String> qs = new ArrayList<>();
        for (ContentResult.QuestionSummary q : c.questions()) {
            qs.add(new Json().str("label", q.label()).num("answers", q.answers()).num("signal", q.signal())
                    .num("pairs", q.pairs()).num("tas", q.assistants()).toString());
        }
        j.raw("questions", Json.array(qs));
        List<String> sev = new ArrayList<>();
        for (int k = 0; k < c.graderLabels().size(); k++) {
            double[] ci = c.interval95(k);
            sev.add(new Json().str("label", c.graderLabels().get(k)).num("severity", c.severity()[k]).num("lo", ci[0])
                    .num("hi", ci[1]).num("pairs", c.pairCount()[k]).toString());
        }
        j.raw("severity", Json.array(sev));
        j.raw("sameAnswer", pairs(c.sameAnswerDifferentMarks()));
        j.raw("sameTa", pairs(c.sameAssistantInconsistent()));
        List<String> out = new ArrayList<>();
        for (ContentResult.OutOfLine o : c.outOfLine()) {
            out.add(new Json().str("question", o.question()).num("max", o.maxMarks()).str("student", o.student())
                    .str("ta", o.assistant()).num("marks", o.marks()).num("expected", o.expectedMarks()).num("z", o.z())
                    .str("excerpt", o.excerpt()).toString());
        }
        j.raw("outOfLine", Json.array(out));
        c.causal().ifPresent(f -> j.raw("causal", causal(f)));
        return j.toString();
    }

    private static String pairs(List<ContentResult.PairEvidence> list) {
        List<String> out = new ArrayList<>();
        for (ContentResult.PairEvidence p : list) {
            out.add(new Json().str("question", p.question()).num("max", p.maxMarks()).num("similarity", p.similarity())
                    .num("gap", p.gapPoints()).raw("first", side(p.first())).raw("second", side(p.second())).toString());
        }
        return Json.array(out);
    }

    private static String side(ContentResult.Side s) {
        return new Json().str("student", s.student()).str("ta", s.assistant()).num("marks", s.marks())
                .str("excerpt", s.excerpt()).toString();
    }

    private static String causal(CausalFindings f) {
        Json j = new Json();
        j.raw("variance", new Json().num("content", f.variance().content()).num("ta", f.variance().assistant())
                .num("residual", f.variance().residual()).toString());
        List<String> dec = new ArrayList<>();
        for (CausalFindings.Decomposition d : f.decomposition()) {
            dec.add(new Json().str("ta", d.assistant()).num("answers", d.answers()).num("raw", d.rawGap())
                    .num("content", d.contentGap()).num("marking", d.markingGap()).num("unexplained", d.unexplained()).toString());
        }
        j.raw("decomposition", Json.array(dec));
        List<String> ov = new ArrayList<>();
        for (CausalFindings.Overlap o : f.overlap()) {
            ov.add(new Json().str("ta", o.assistant()).num("matchedShare", o.matchedShare()).num("before", o.balanceBefore())
                    .num("after", o.balanceAfter()).num("length", o.lengthAfter()).toString());
        }
        j.raw("overlap", Json.array(ov));
        f.doubleMarked().ifPresent(dm -> j.raw("doubleMarked", new Json().num("scripts", dm.scripts())
                .num("agreement", dm.agreement()).toString()));
        j.raw("placebo", new Json().num("p", f.placebo().pValue()).num("shuffles", f.placebo().shuffles())
                .num("observed", f.placebo().observedSpread()).num("shuffled", f.placebo().meanSpreadUnderShuffle()).toString());
        List<String> sens = new ArrayList<>();
        for (CausalFindings.Sensitivity s : f.sensitivity()) {
            sens.add(new Json().str("ta", s.assistant()).num("pairs", s.pairs()).num("direction", s.direction())
                    .num("p", s.pAtOne()).num("gamma", s.gamma()).toString());
        }
        j.raw("sensitivity", Json.array(sens));
        List<String> mech = new ArrayList<>();
        for (CausalFindings.Mechanism m : f.mechanisms()) {
            List<String> qs = new ArrayList<>();
            for (CausalFindings.QuestionEffect e : m.byQuestion()) {
                qs.add(new Json().str("question", e.question()).num("severity", e.severity()).num("se", e.standardError())
                        .num("pairs", e.pairs()).toString());
            }
            mech.add(new Json().str("ta", m.assistant()).str("kind", m.kind().name()).num("heterogeneityP", m.heterogeneityP())
                    .num("slips", m.slips()).str("explanation", m.explanation()).raw("byQuestion", Json.array(qs)).toString());
        }
        j.raw("mechanisms", Json.array(mech));
        return j.toString();
    }

    private static String outcome(Outcome.Result o) {
        Json j = new Json().num("share", o.assistantShare()).num("changed", o.changed()).num("low", o.changedLow())
                .num("high", o.changedHigh());
        Map<String, int[]> grades = new LinkedHashMap<>();
        for (LetterGrade g : LetterGrade.awardable()) grades.put(g.name(), new int[2]);
        for (LetterGrade g : o.gradesAsMarked()) if (grades.containsKey(g.name())) grades.get(g.name())[0]++;
        for (LetterGrade g : o.gradesCorrected()) if (grades.containsKey(g.name())) grades.get(g.name())[1]++;
        List<String> rows = new ArrayList<>();
        grades.forEach((g, n) -> rows.add(new Json().str("grade", g).num("asMarked", n[0]).num("corrected", n[1]).toString()));
        j.raw("grades", Json.array(rows));
        switch (o.shortlist()) {
            case Outcome.Refused r -> j.str("refused", r.reason());
            case Outcome.Emitted e -> {
                List<String> list = new ArrayList<>();
                for (Outcome.Row r : e.rows()) {
                    list.add(new Json().str("student", r.student()).num("total", r.totalAsMarked())
                            .num("corrected", r.totalCorrected()).str("grade", r.gradeAsMarked().name())
                            .str("likely", r.likelyGrade().name()).num("probability", r.probability())
                            .num("points", r.expectedPoints()).toString());
                }
                j.raw("recheck", Json.array(list)).num("expectedFalseFlags", e.expectedFalseFlags());
            }
        }
        return j.toString();
    }

    private static String diagnostics(List<Diagnostic> diagnostics) {
        Map<String, List<Diagnostic>> byKind = new LinkedHashMap<>();
        for (Diagnostic d : diagnostics) byKind.computeIfAbsent(HtmlReport.kindOf(d), k -> new ArrayList<>()).add(d);
        List<String> out = new ArrayList<>();
        for (List<Diagnostic> group : byKind.values()) {
            Diagnostic d = group.get(0);
            out.add(new Json().str("severity", d.severity().name().toLowerCase(Locale.ROOT)).str("message", d.message())
                    .str("fix", d.fixIt().orElse("")).num("more", group.size() - 1).toString());
        }
        return Json.array(out);
    }
}
