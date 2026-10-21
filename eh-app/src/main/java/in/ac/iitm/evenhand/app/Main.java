package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.content.CausalFindings;
import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.embed.Embedders;
import in.ac.iitm.evenhand.ingest.PseudonymKey;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.ingest.XlsxReader;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.HtmlReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The command-line tool.
 *
 * <pre>
 *   java -jar even-hand.jar serve
 *   java -jar even-hand.jar run marks.csv --weights "Quiz 1=25,Quiz 2=25,End-sem=50" [--rule relative] [--out dir]
 *   java -jar even-hand.jar model
 * </pre>
 */
public final class Main {

    private static final String USAGE = """
            Even Hand: a grading-consistency audit for IIT Madras courses.

              serve [--port 8642]                 opens the tool in your browser (this computer only)

              run <marks.csv|.xlsx> [--name "CE3020 Jul-Nov 2026"] [--weights "Quiz 1=25,Quiz 2=25,End-sem=50"]
                  [--rule relative | relative-sigma | absolute:S=90,A=80,B=70,C=60,D=50,E=40]
                  [--out <dir>] [--seal-key-with-env <VAR>]
                  Audits the marking, reads the written answers if the file has them, estimates each TA's
                  severity, explains why marks differed, regrades under the course's rule, and writes
                  report.html (instructor) and committee.html (Class Committee).

              model                               says which answer-reading model is installed
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            return;
        }
        Map<String, String> flags = flags(args);
        switch (args[0]) {
            case "run" -> {
                if (args.length < 2 || args[1].startsWith("--")) {
                    throw new IllegalArgumentException("run needs a marks file (.csv or .xlsx)");
                }
                run(Path.of(args[1]), flags);
            }
            case "serve" -> serve(Integer.parseInt(flags.getOrDefault("port", String.valueOf(LocalServer.DEFAULT_PORT))));
            case "model" -> {
                Embedders.Choice c = Embedders.best();
                System.out.println(c.embedder().id() + "\n" + c.note());
            }
            default -> {
                System.err.print(USAGE);
                System.exit(2);
            }
        }
    }

    /** Starts the local page and opens it in the instructor's browser. */
    static void serve(int port) throws IOException {
        String url = "http://127.0.0.1:" + port + "/";
        try {
            LocalServer.start(port);
            System.out.println("Even Hand is open at " + url + "  (this computer only; close this window to stop)");
            // Load the answer-reading model now, so the first analysis does not wait for it.
            Thread warm = new Thread(Embedders::best, "model-loader");
            warm.setDaemon(true);
            warm.start();
        } catch (java.net.BindException e) {
            // Usually a second double-click: Even Hand is already running, so just show it again.
            System.out.println("Even Hand is already running at " + url + "; opening it in your browser.");
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            System.out.println("Open that address in your browser.");
        }
    }

    static void run(Path csv, Map<String, String> flags) throws IOException {
        String name = flags.getOrDefault("name", csv.getFileName().toString().replaceFirst("(?i)\\.(csv|xlsx)$", ""));
        CourseConfig config = new CourseConfig(name,
                flags.containsKey("weights") ? CourseConfig.parseWeights(flags.get("weights")) : Map.of(),
                CourseConfig.parseRule(flags.getOrDefault("rule", "relative")),
                flags.containsKey("rule"));
        Path out = Path.of(flags.getOrDefault("out", "even-hand-report"));

        byte[] bytes = Files.readAllBytes(csv);
        String text = XlsxReader.looksLikeXlsx(bytes) ? XlsxReader.toCsv(bytes) : new String(bytes, StandardCharsets.UTF_8);
        Pipeline.Run result = Pipeline.run(text, Pseudonymiser.withRandomSalt(), config, Pipeline.Options.defaults(),
                (stage, fraction) -> System.err.printf(Locale.ROOT, "\r%3.0f%%  %-70s", 100 * fraction, stage));
        System.err.println();

        Files.createDirectories(out);
        Files.writeString(out.resolve("report.html"), HtmlReport.instructor(result.report()), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("committee.html"), HtmlReport.committee(result.report()), StandardCharsets.UTF_8);
        sealKey(result, flags, out);
        printSummary(result, PseudonymKey.of(result.ingest().keyFileContents()));
        System.out.println("\nWrote " + out.resolve("report.html") + " and " + out.resolve("committee.html"));
    }

    /**
     * The terminal is the one place real identifiers are shown, once, to the instructor
     * running the tool. They are never written to disk except inside the sealed key file.
     */
    private static void printSummary(Pipeline.Run r, PseudonymKey key) {
        System.out.println("AUDIT     " + r.markingVerdict().headline());
        System.out.println("          " + r.verdict().exposure().headline());
        r.content().ifPresent(c -> printContent(c, key));
        if (r.model().isEmpty()) {
            System.out.println("ESTIMATE  not run: the arrangement cannot support it.");
            return;
        }
        FittedModel m = r.model().get();
        System.out.println("ESTIMATE  severity, percentage points of a question's maximum (+ = harsh), "
                + (r.report().anchoredByAnswers() ? "from matched answers:" : "from the marks:"));
        for (int j = 0; j < m.graders().size(); j++) {
            double[] ci = m.interval95(j);
            String label = m.graders().labels().get(j);
            System.out.printf(Locale.ROOT, "          %-13s %-14s %+6.1f  [%+.1f, %+.1f]  %d marks%n",
                    label, key.real(label), m.severity(j), ci[0], ci[1], m.graders().counts()[j]);
        }
        System.out.printf(Locale.ROOT, "          no-effect test p = %.3f%n", m.noEffect().pValue());
        Outcome.Result o = r.outcome().orElseThrow();
        System.out.printf(Locale.ROOT, "OUTCOME   %.1f%% of the spread in totals came from who marked; %d grade(s) "
                + "would change (90%% range %d-%d)%n", o.assistantShare(), o.changed(), o.changedLow(), o.changedHigh());
        switch (o.shortlist()) {
            case Outcome.Refused ref -> System.out.println("RE-CHECK  none: " + ref.reason());
            case Outcome.Emitted e -> {
                System.out.println("RE-CHECK  " + e.rows().size() + " script(s), most consequential first:");
                for (Outcome.Row row : e.rows()) {
                    System.out.printf(Locale.ROOT, "          %-10s %-13s %s -> %s  %3.0f%%  total %.1f -> %.1f%n",
                            key.real(row.student()), row.student(), row.gradeAsMarked(), row.likelyGrade(),
                            100 * row.probability(), row.totalAsMarked(), row.totalCorrected());
                }
            }
        }
    }

    private static void printContent(ContentResult c, PseudonymKey key) {
        System.out.printf(Locale.ROOT, "ANSWERS   %s: %.0f%% of responses carry text; content explains %.0f%% of marks "
                + "within a pile; %.0f%% of answers have a close match marked by another TA%n",
                c.status(), 100 * c.textCoverage(), 100 * c.signal(), 100 * c.matchedShare());
        c.causal().ifPresent(f -> {
            CausalFindings.VarianceShare v = f.variance();
            System.out.printf(Locale.ROOT, "WHY       of the variation in marks: %.0f%% what students wrote, %.0f%% who "
                    + "marked it, %.0f%% neither%n", v.content(), v.assistant(), v.residual());
            for (CausalFindings.Mechanism m : f.mechanisms()) {
                System.out.println("          " + key.real(m.assistant()) + ": " + m.explanation());
            }
            System.out.printf(Locale.ROOT, "          placebo (labels shuffled among similar answers): p = %.3f%n",
                    f.placebo().pValue());
        });
        for (String n : c.notes()) System.out.println("          note: " + n);
    }

    private static void sealKey(Pipeline.Run r, Map<String, String> flags, Path out) throws IOException {
        String variable = flags.get("seal-key-with-env");
        if (variable == null) {
            System.out.println("(Key not saved: pass --seal-key-with-env VAR, with your passphrase in VAR, to keep "
                    + "the pseudonym key for a later run.)");
            return;
        }
        String passphrase = System.getenv(variable);
        if (passphrase == null) throw new IllegalArgumentException("environment variable " + variable + " is not set");
        Files.write(out.resolve("course.key"), r.ingest().sealedKeyFile(passphrase.toCharArray()));
    }

    /** {@code --key value} pairs after the positional arguments. */
    static Map<String, String> flags(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(args[i] + " needs a value");
                out.put(args[i].substring(2), args[++i]);
            }
        }
        return out;
    }
}
