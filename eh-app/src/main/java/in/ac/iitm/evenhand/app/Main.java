package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.design.AuditReport;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.HtmlReport;

import java.io.BufferedReader;
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
 *   java -jar even-hand.jar simulate ce-core 7 samples/
 *   java -jar even-hand.jar benchmark [--out docs]
 *   java -jar even-hand.jar scenarios
 * </pre>
 */
public final class Main {

    private static final String USAGE = """
            Even Hand: a grading-consistency audit for IIT Madras courses.

              serve [--port 8642]                 opens the tool in your browser (this computer only)

              run <marks.csv> [--name "CE3020 Jul-Nov 2026"] [--weights "Quiz 1=25,Quiz 2=25,End-sem=50"]
                  [--rule relative | relative-sigma | absolute:S=90,A=80,B=70,C=60,D=50,E=40]
                  [--out <dir>] [--seal-key-with-env <VAR>]
                  Audits the marking, estimates each assistant's severity, regrades under the course's
                  rule and writes report.html (instructor) and committee.html (Class Committee).

              simulate <archetype> <seed> <dir>   writes a synthetic IITM course and its answers
                  archetypes: %s
              benchmark [--out <dir>] [--train N] [--test M]   trains and tests on synthetic courses
              scenarios                           prints the audit's verdict on twelve arrangements
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.printf(USAGE, archetypeNames());
            return;
        }
        Map<String, String> flags = flags(args);
        switch (args[0]) {
            case "run" -> run(Path.of(args[1]), flags);
            case "serve" -> serve(Integer.parseInt(flags.getOrDefault("port", String.valueOf(LocalServer.DEFAULT_PORT))));
            case "simulate" -> simulate(args[1], Long.parseLong(args[2]), Path.of(args[3]));
            case "benchmark" -> Benchmark.main(flags);
            case "scenarios" -> AuditReport.main(new String[0]);
            default -> {
                System.err.printf(USAGE, archetypeNames());
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
        String name = flags.getOrDefault("name", csv.getFileName().toString().replaceFirst("\\.csv$", ""));
        CourseConfig config = new CourseConfig(name,
                flags.containsKey("weights") ? CourseConfig.parseWeights(flags.get("weights")) : Map.of(),
                CourseConfig.parseRule(flags.getOrDefault("rule", "relative")),
                flags.containsKey("rule"));
        Path out = Path.of(flags.getOrDefault("out", "even-hand-report"));

        Pseudonymiser pseudonymiser = Pseudonymiser.withRandomSalt();
        Pipeline.Run result;
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            result = Pipeline.run(reader, pseudonymiser, config, Settings.defaults());
        }

        Files.createDirectories(out);
        Files.writeString(out.resolve("report.html"), HtmlReport.instructor(result.report()), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("committee.html"), HtmlReport.committee(result.report()), StandardCharsets.UTF_8);
        sealKey(result, flags, out);
        printSummary(result, KeyLookup.of(result.ingest().keyFileContents()));
        System.out.println("\nWrote " + out.resolve("report.html") + " and " + out.resolve("committee.html"));
    }

    /**
     * The terminal is the one place real identifiers are shown, once, to the instructor
     * running the tool. They are never written to disk except inside the sealed key file.
     */
    private static void printSummary(Pipeline.Run r, KeyLookup key) {
        System.out.println("AUDIT     " + r.verdict().headline());
        System.out.println("          " + r.verdict().exposure().headline());
        if (r.model().isEmpty()) {
            System.out.println("ESTIMATE  not run: the arrangement cannot support it.");
            return;
        }
        FittedModel m = r.model().get();
        System.out.println("ESTIMATE  severity, percentage points of a question's maximum (+ = harsh):");
        for (int j = 0; j < m.graders().size(); j++) {
            double[] ci = m.interval95(j);
            String label = m.graders().labels().get(j);
            System.out.printf(Locale.ROOT, "          %-13s %-10s %+6.1f  [%+.1f, %+.1f]  %d marks%n",
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

    static void simulate(String archetype, long seed, Path dir) throws IOException {
        CourseSimulator.Archetype a = CourseSimulator.Archetype.valueOf(archetype.toUpperCase(Locale.ROOT).replace('-', '_'));
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(a, seed);
        Files.createDirectories(dir);
        course.writeMarksCsv(dir.resolve(course.name() + ".csv"));
        course.writeTruthCsv(dir.resolve(course.name() + ".truth.csv"));
        StringBuilder weights = new StringBuilder();
        course.weights().forEach((k, v) -> weights.append(weights.length() == 0 ? "" : ",").append(k).append('=')
                .append(v.intValue()));
        System.out.println("Wrote " + dir.resolve(course.name() + ".csv") + "  (" + a.description + ")");
        System.out.println("Run it with: run " + dir.resolve(course.name() + ".csv") + " --weights \"" + weights + "\"");
    }

    private static String archetypeNames() {
        StringBuilder sb = new StringBuilder();
        for (CourseSimulator.Archetype a : CourseSimulator.Archetype.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return sb.toString();
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

    /** Pseudonym to real identifier, from the key held in memory for this run only. */
    record KeyLookup(Map<String, String> realByLabel) {
        static KeyLookup of(String keyFileContents) {
            Map<String, String> map = new HashMap<>();
            boolean inTable = false;
            for (String line : keyFileContents.split("\n")) {
                if (line.startsWith("identifier,label,digest")) {
                    inTable = true;
                    continue;
                }
                if (!inTable || line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 3) map.put(parts[parts.length - 2], String.join(",",
                        java.util.Arrays.copyOf(parts, parts.length - 2)));
            }
            return new KeyLookup(map);
        }

        String real(String label) {
            return realByLabel.getOrDefault(label, "?");
        }
    }
}
