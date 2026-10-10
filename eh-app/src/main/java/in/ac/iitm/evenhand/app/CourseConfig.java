package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.LetterGrade;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the instructor tells us about the course: its name, how much each assessment
 * counts, and how marks become letters. Anything not given is defaulted and the report
 * says so.
 *
 * @param weights      assessment name to weight, e.g. {@code Quiz 1=25}; empty means equal
 * @param policy       the cut-off rule
 * @param ruleDeclared whether the instructor named the rule, rather than accepting the default
 */
public record CourseConfig(String name, Map<String, Double> weights, CutoffPolicy policy, boolean ruleDeclared) {

    public CourseConfig {
        weights = Map.copyOf(weights);
    }

    public static CourseConfig defaults(String name) {
        return new CourseConfig(name, Map.of(), CutoffPolicy.iitmConventionalRelative(), false);
    }

    /** Parses {@code "Quiz 1=25, Quiz 2=25, End-sem=50"}. */
    public static Map<String, Double> parseWeights(String spec) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            if (kv.length != 2) throw new IllegalArgumentException("weight '" + part.strip() + "' is not name=number");
            out.put(kv[0].strip(), Double.parseDouble(kv[1].strip()));
        }
        return out;
    }

    /**
     * Parses a rule.
     * <ul>
     *   <li>{@code relative}: B at the mean, A/S one/two SD above, C/D/E one/two/three below,
     *       top 10% get S (the convention our stakeholder described)</li>
     *   <li>{@code relative-sigma}: the same bands with no fixed share for S</li>
     *   <li>{@code absolute:S=90,A=80,B=70,C=60,D=50,E=40}: fixed thresholds on the total</li>
     * </ul>
     */
    public static CutoffPolicy parseRule(String spec) {
        String s = spec.strip().toLowerCase(Locale.ROOT);
        if (s.equals("relative")) return CutoffPolicy.iitmConventionalRelative();
        if (s.equals("relative-sigma")) {
            CutoffPolicy r = CutoffPolicy.iitmConventionalRelative();
            return new CutoffPolicy(r.kind(), r.anchorGrade(), r.bandOffsets(), 0, Map.of(),
                    CutoffPolicy.SBandPolicy.SIGMA_BINDS);
        }
        if (s.startsWith("absolute:")) {
            Map<LetterGrade, Double> thresholds = new LinkedHashMap<>();
            parseWeights(spec.strip().substring("absolute:".length()))
                    .forEach((k, v) -> thresholds.put(LetterGrade.valueOf(k.toUpperCase(Locale.ROOT)), v));
            return CutoffPolicy.absolute(thresholds);
        }
        throw new IllegalArgumentException("unknown rule '" + spec + "': use relative, relative-sigma or absolute:S=90,...");
    }
}
