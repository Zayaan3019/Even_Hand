package in.ac.iitm.evenhand.ingest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How roll numbers order, and what department they name.
 *
 * <p>Used only inside ingest, while real identifiers are still in memory, to compute
 * the allocation evidence; nothing here is retained. The ordering is natural rather
 * than lexicographic, so {@code CE23B9} sorts before {@code CE23B10}, and it is the
 * order an office would print a class list in. The department is the run of letters an
 * IIT Madras roll number starts with ({@code CE} in {@code CE23B092}).
 */
final class RollNumbers {

    private static final Pattern DEPARTMENT = Pattern.compile("^([A-Za-z]+)\\d");

    static final Comparator<String> NATURAL_ORDER = RollNumbers::compare;

    private RollNumbers() {
    }

    static int compare(String a, String b) {
        List<String> x = chunks(a.strip().toUpperCase(Locale.ROOT));
        List<String> y = chunks(b.strip().toUpperCase(Locale.ROOT));
        for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
            String p = x.get(i);
            String q = y.get(i);
            int c;
            if (Character.isDigit(p.charAt(0)) && Character.isDigit(q.charAt(0))) {
                c = new BigInteger(p).compareTo(new BigInteger(q));
            } else {
                c = p.compareTo(q);
            }
            if (c != 0) return c;
        }
        int c = Integer.compare(x.size(), y.size());
        return c != 0 ? c : a.compareTo(b);
    }

    /** The department code a roll number starts with, or null when it does not look like one. */
    static String department(String roll) {
        Matcher m = DEPARTMENT.matcher(roll.strip());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static List<String> chunks(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            boolean digit = Character.isDigit(s.charAt(i));
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j)) == digit) j++;
            out.add(s.substring(i, j));
            i = j;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }
}
