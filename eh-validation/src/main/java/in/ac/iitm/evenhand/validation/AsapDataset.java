package in.ac.iitm.evenhand.validation;

import in.ac.iitm.evenhand.ingest.XlsxReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Automated Student Assessment Prize short-answer data (ASAP-SAS, Hewlett Foundation, 2012), training
 * set: 17,207 real answers by US school students to 10 questions, each with two human scores
 * ({@code Score1}, {@code Score2}). Republished under CC BY 4.0 at Zenodo (doi:10.5281/zenodo.7641696);
 * fetched by {@code scripts/validate.sh}, never committed.
 *
 * <p>It is a deliberately different test from the UNT data, and nothing was tuned on it: younger students,
 * science and reading rather than computer science, no answer key, and each student answers one question
 * only, so the marks alone say almost nothing about ability. Read through the product's own Excel reader.
 */
final class AsapDataset {

    private AsapDataset() {
    }

    static UntDataset load(Path xlsx) throws IOException {
        List<List<String>> rows = parse(XlsxReader.toCsv(Files.readAllBytes(xlsx)));
        List<String> header = rows.get(0);
        int set = header.indexOf("EssaySet"), first = header.indexOf("Score1"), second = header.indexOf("Score2"),
                text = header.indexOf("EssayText");
        if (set < 0 || first < 0 || second < 0 || text < 0) throw new IOException("unexpected columns: " + header);
        Map<Integer, List<UntDataset.Answer>> byQuestion = new LinkedHashMap<>();
        Map<Integer, Double> max = new LinkedHashMap<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= text || r.get(text).isBlank()) continue;
            int q = (int) Double.parseDouble(r.get(set));
            double a = Double.parseDouble(r.get(first)), b = Double.parseDouble(r.get(second));
            byQuestion.computeIfAbsent(q, k -> new ArrayList<>()).add(new UntDataset.Answer(r.get(text).strip(), a, b));
            max.merge(q, Math.max(a, b), Math::max);
        }
        List<UntDataset.Question> out = new ArrayList<>();
        byQuestion.forEach((q, answers) -> out.add(new UntDataset.Question(q + ".1", max.get(q), "", answers)));
        return new UntDataset(out);
    }

    /** RFC 4180, quoted fields spanning lines included. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n') {
                row.add(f.toString());
                f.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                f.append(c);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }
}
