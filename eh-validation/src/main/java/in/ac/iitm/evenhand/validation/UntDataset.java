package in.ac.iitm.evenhand.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The UNT Computer Science Short Answer Dataset, version 2.0 (Mohler, Bunescu and Mihalcea,
 * ACL 2011): 2,273 real answers by students of an introductory data-structures course at the
 * University of North Texas to 81 questions, <b>each answer graded independently by two people</b>:
 * the course's TA ({@code scores/x.y/other}) and a second grader ({@code scores/x.y/me}).
 *
 * <p>Because both graded everything, how differently they graded is known exactly. That is the
 * ground truth a real course never has, on real answers and real grading behaviour. Fetched by
 * {@code scripts/fetch-validation-data}; never committed.
 */
record UntDataset(List<Question> questions) {

    /** One answer: the text and both people's grades, on the question's own scale. */
    record Answer(String text, double ta, double second) {
        double average() {
            return (ta + second) / 2;
        }
    }

    /** Assignments 1-10 were graded 0..5, the two exams (11, 12) 0..10. */
    record Question(String id, double maxMarks, List<Answer> answers) {}

    static UntDataset load(Path root) throws IOException {
        Path data = Files.isDirectory(root.resolve("data")) ? root.resolve("data") : root;
        List<Question> out = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("docs").resolve("files"), StandardCharsets.UTF_8)) {
            String id = line.strip();
            if (id.isEmpty() || id.startsWith("#")) continue;   // the authors' own exclusions: not short answers
            List<String> raw = Files.readAllLines(data.resolve("raw").resolve(id), StandardCharsets.ISO_8859_1);
            List<String> ta = Files.readAllLines(data.resolve("scores").resolve(id).resolve("other"));
            List<String> second = Files.readAllLines(data.resolve("scores").resolve(id).resolve("me"));
            raw.removeIf(String::isBlank);
            ta.removeIf(String::isBlank);
            second.removeIf(String::isBlank);
            if (raw.size() != ta.size() || raw.size() != second.size()) {
                throw new IOException("question " + id + ": " + raw.size() + " answers but " + ta.size() + "/"
                        + second.size() + " grades");
            }
            double max = Integer.parseInt(id.substring(0, id.indexOf('.'))) >= 11 ? 10 : 5;
            List<Answer> answers = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                String text = raw.get(i).strip();
                if (text.startsWith(id + " ")) text = text.substring(id.length() + 1);
                text = text.replace("<br>", "\n").strip();
                double a = Double.parseDouble(ta.get(i).strip()), b = Double.parseDouble(second.get(i).strip());
                answers.add(new Answer(text, Math.min(max, a), Math.min(max, b)));
            }
            out.add(new Question(id, max, answers));
        }
        return new UntDataset(out);
    }

    int answerCount() {
        return questions.stream().mapToInt(q -> q.answers().size()).sum();
    }
}
