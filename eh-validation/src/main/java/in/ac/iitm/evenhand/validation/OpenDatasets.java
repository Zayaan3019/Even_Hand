package in.ac.iitm.evenhand.validation;

import in.ac.iitm.evenhand.ingest.XlsxReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The held-out datasets of {@code docs/PREREGISTRATION.md}, each read into the shape the validation uses. All are
 * fetched and checked by the {@code validate} workflow; none is committed, and none reaches the product.
 */
final class OpenDatasets {

    private OpenDatasets() {
    }

    /**
     * ASAP essays (Hewlett Foundation, CC BY 4.0, doi:10.5281/zenodo.7641696): {@code rater1_domain1} and
     * {@code rater2_domain1} on each essay's own scale. Essays run far past what the text model reads.
     */
    static UntDataset asapEssays(Path xlsx) throws IOException {
        List<List<String>> rows = AsapDataset.parse(XlsxReader.toCsv(Files.readAllBytes(xlsx)));
        List<String> h = rows.get(0);
        int set = column(h, "essay_set"), first = column(h, "rater1_domain1"), second = column(h, "rater2_domain1"),
                text = column(h, "essay");
        Map<Integer, List<UntDataset.Answer>> byQuestion = new LinkedHashMap<>();
        Map<Integer, Double> max = new LinkedHashMap<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= Math.max(text, Math.max(first, second)) || r.get(text).isBlank()) continue;
            if (r.get(first).isBlank() || r.get(second).isBlank()) continue;
            int q = (int) Double.parseDouble(r.get(set));
            double a = Double.parseDouble(r.get(first)), b = Double.parseDouble(r.get(second));
            byQuestion.computeIfAbsent(q, k -> new ArrayList<>()).add(new UntDataset.Answer(r.get(text).strip(), a, b));
            max.merge(q, Math.max(a, b), Math::max);
        }
        List<UntDataset.Question> out = new ArrayList<>();
        byQuestion.forEach((q, answers) -> out.add(new UntDataset.Question(q + ".1", max.get(q), "", answers)));
        return new UntDataset(out);
    }

    /**
     * JorGPT (Universidad Francisco de Vitoria, 2025-26, CC BY 4.0, doi:10.5281/zenodo.18981627): real students across
     * real questions, marked 0..10 by their teacher. The only second opinion is a language model's, so the teacher is
     * the "second" grader the single-grader scenarios use, and the model is labelled as what it is wherever it is used.
     * Rows where the model gave no grade are left out.
     */
    static UntDataset jorGpt(Path csv) throws IOException {
        List<List<String>> rows = AsapDataset.parse(Files.readString(csv, StandardCharsets.UTF_8));
        List<String> h = rows.get(0);
        int question = column(h, "question_id"), student = column(h, "student_anom_id"), answer = column(h, "student_answer"),
                key = column(h, "ideal_answer"), teacher = column(h, "teacher_grade"), model = column(h, "judge_grade");
        int last = Math.max(Math.max(question, student), Math.max(Math.max(answer, key), Math.max(teacher, model)));
        Map<String, Integer> students = new LinkedHashMap<>();
        Map<String, List<UntDataset.Answer>> byQuestion = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= last || r.get(answer).isBlank() || r.get(teacher).isBlank() || r.get(model).isBlank()) continue;
            int s = students.computeIfAbsent(r.get(student), k -> students.size());
            keys.putIfAbsent(r.get(question), r.get(key).strip());
            byQuestion.computeIfAbsent(r.get(question), k -> new ArrayList<>()).add(new UntDataset.Answer(
                    r.get(answer).strip(), clamp(r.get(model), 10), clamp(r.get(teacher), 10), Double.NaN, s));
        }
        List<UntDataset.Question> out = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, List<UntDataset.Answer>> e : byQuestion.entrySet()) {
            out.add(new UntDataset.Question("1." + (++n), 10, keys.get(e.getKey()), e.getValue()));
        }
        return new UntDataset(out);
    }

    /**
     * CityU Operating Systems (Xie, Niu, Xue and Guan, arXiv:2405.19694): each answer marked independently and blind by
     * three real TAs. The repository names no licence, so the data is fetched from its source at a pinned commit by the
     * workflow, cited, never redistributed, and only aggregate figures are reported.
     *
     * @param csv from {@code scripts/cityu-to-csv.py}: question, full_points, sample_answer, answer, score_1, score_2, score_3
     */
    static UntDataset cityU(Path csv) throws IOException {
        List<List<String>> rows = AsapDataset.parse(Files.readString(csv, StandardCharsets.UTF_8));
        List<String> h = rows.get(0);
        int question = column(h, "question"), full = column(h, "full_points"), key = column(h, "sample_answer"),
                answer = column(h, "answer"), one = column(h, "score_1"), two = column(h, "score_2"), three = column(h, "score_3");
        Map<String, List<UntDataset.Answer>> byQuestion = new LinkedHashMap<>();
        Map<String, Double> max = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= three || r.get(answer).isBlank()) continue;
            double m = Double.parseDouble(r.get(full));
            max.put(r.get(question), m);
            keys.putIfAbsent(r.get(question), r.get(key).strip());
            byQuestion.computeIfAbsent(r.get(question), k -> new ArrayList<>()).add(new UntDataset.Answer(
                    r.get(answer).strip(), clamp(r.get(one), m), clamp(r.get(two), m), clamp(r.get(three), m), -1));
        }
        List<UntDataset.Question> out = new ArrayList<>();
        byQuestion.forEach((q, answers) -> out.add(new UntDataset.Question(q + ".1", max.get(q), keys.get(q), answers)));
        return new UntDataset(out);
    }

    private static double clamp(String mark, double max) {
        return Math.max(0, Math.min(max, Double.parseDouble(mark)));
    }

    private static int column(List<String> header, String name) throws IOException {
        int at = header.indexOf(name);
        if (at < 0) throw new IOException("no column " + name + " in " + header);
        return at;
    }
}
