package in.ac.iitm.evenhand.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes identities out of what students wrote, before any later stage reads it.
 *
 * <p>Students write their roll number at the top of an answer, sign off with their e-mail, or
 * name the TA ("as Ravi sir explained"). Pseudonymising the columns is not enough if the same
 * identifiers ride along inside the text, so every roll number and TA name that occurs in the
 * file, anything shaped like an IITM roll number, and every e-mail address, are replaced by
 * placeholders. Matching is whole-word and case-insensitive.
 */
final class AnswerScrubber {

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    /** CE23B092, me21m015, ae24d401: department, year, programme letter, serial. */
    private static final Pattern ROLL_SHAPE = Pattern.compile("\\b[A-Za-z]{2}\\d{2}[A-Za-z]\\d{3}\\b");
    /** Names shorter than this are left alone: "TA 1" or "Al" would also strike ordinary words. */
    private static final int MIN_NAME_LENGTH = 3;

    private final Pattern knownStudents;
    private final Pattern knownGraders;
    private int replacements;

    AnswerScrubber(Collection<String> students, Collection<String> graders) {
        this.knownStudents = wholeWords(students);
        this.knownGraders = wholeWords(graders);
    }

    String scrub(String answer) {
        if (answer.isBlank()) return "";
        String out = replace(EMAIL, answer, "[email]");
        if (knownStudents != null) out = replace(knownStudents, out, "[roll number]");
        if (knownGraders != null) out = replace(knownGraders, out, "[name]");
        out = replace(ROLL_SHAPE, out, "[roll number]");
        return out;
    }

    /** Any of the words, case-insensitively, not inside a longer word; longest first. */
    private static Pattern wholeWords(Collection<String> words) {
        List<String> sorted = new ArrayList<>();
        for (String w : words) if (w.strip().length() >= MIN_NAME_LENGTH) sorted.add(w.strip());
        if (sorted.isEmpty()) return null;
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alt = new StringBuilder();
        for (String w : sorted) {
            if (!alt.isEmpty()) alt.append('|');
            alt.append(Pattern.quote(w));
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alt + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** How many identifiers have been replaced so far, for the reader's note. */
    int replacements() {
        return replacements;
    }

    private String replace(Pattern p, String text, String placeholder) {
        Matcher m = p.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            replacements++;
            m.appendReplacement(b, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(b);
        return b.toString();
    }
}
