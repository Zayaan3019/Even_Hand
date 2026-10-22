package in.ac.iitm.evenhand.embed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the embedding model on this computer, checks that it is exactly the file the
 * validation was run on, and loads it once.
 *
 * <p>Search order: the {@code EVEN_HAND_MODEL} environment variable or {@code evenhand.model}
 * system property (a folder), then {@code models/all-MiniLM-L6-v2} under the working folder,
 * then {@code ~/.even-hand/models/all-MiniLM-L6-v2}. The launchers fetch it to the last place on
 * first run ({@code scripts/fetch-model}); the program itself never downloads anything.
 *
 * <p>The weights are checked against a pinned SHA-256. A model that differs from the one the
 * accuracy figures were measured with is not silently used: it is reported, and the lexical
 * embedder stands in, unless {@code EVEN_HAND_MODEL_UNPINNED=1} says the change is deliberate.
 */
public final class Embedders {

    /** A model the figures in docs/VALIDATION.md were measured with. */
    public record Pinned(String name, String repository, String revision, Map<String, String> sha256) {}

    public static final Pinned MINILM = new Pinned("all-MiniLM-L6-v2",
            "sentence-transformers/all-MiniLM-L6-v2", "1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
            Map.of("model.safetensors", "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db",
                   "vocab.txt", "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
                   "config.json", "953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41"));

    /** What was loaded, or why the fallback is in use; the report quotes it. */
    public record Choice(SentenceEmbedder embedder, String note) {}

    private static volatile Choice cached;

    private Embedders() {
    }

    /** The best embedder available, loaded once per process. */
    public static Choice best() {
        Choice c = cached;
        if (c == null) {
            synchronized (Embedders.class) {
                c = cached;
                if (c == null) cached = c = locate();
            }
        }
        return c;
    }

    /**
     * Which linear-algebra kernel reads answers: "SIMD (256-bit)", or the portable one with the reason,
     * so a run that is slower than it should be can be diagnosed from its report.
     */
    public static String kernel() {
        String reason = Kernels.fallbackReason();
        return Kernels.best().name() + (reason.isEmpty() ? "" : " (" + reason + ")");
    }

    /** Folders searched for the model, in order. */
    public static List<Path> searchPath() {
        List<Path> out = new ArrayList<>();
        String explicit = Optional.ofNullable(System.getProperty("evenhand.model")).orElse(System.getenv("EVEN_HAND_MODEL"));
        if (explicit != null && !explicit.isBlank()) out.add(Path.of(explicit.strip()));
        out.add(Path.of("models", MINILM.name()));
        out.add(Path.of(System.getProperty("user.home"), ".even-hand", "models", MINILM.name()));
        return out;
    }

    static Choice locate() {
        boolean unpinned = "1".equals(System.getenv("EVEN_HAND_MODEL_UNPINNED"))
                || "true".equals(System.getProperty("evenhand.model.unpinned"));
        List<String> problems = new ArrayList<>();
        for (Path dir : searchPath()) {
            if (!Files.isRegularFile(dir.resolve("model.safetensors"))) continue;
            try {
                Optional<String> mismatch = verify(dir, MINILM);
                if (mismatch.isPresent() && !unpinned) {
                    problems.add(mismatch.get());
                    continue;
                }
                String id = mismatch.isPresent()
                        ? "unpinned model at " + dir
                        : MINILM.name() + " (" + MINILM.repository() + " @ " + MINILM.revision().substring(0, 7) + ")";
                return new Choice(TransformerEmbedder.load(dir, id), mismatch.map(m -> m + "; used anyway because "
                        + "EVEN_HAND_MODEL_UNPINNED is set").orElse("model checked against its pinned SHA-256"));
            } catch (IOException | RuntimeException e) {
                problems.add("could not load the model at " + dir + ": " + e.getMessage());
            }
        }
        String why = problems.isEmpty()
                ? "no language model is installed (run scripts/fetch-model, or the launcher, once with internet)"
                : String.join("; ", problems);
        return new Choice(new LexicalEmbedder(), "lexical fallback: " + why);
    }

    /** Empty if every pinned file matches; otherwise what differs. */
    static Optional<String> verify(Path dir, Pinned pinned) throws IOException {
        for (Map.Entry<String, String> e : pinned.sha256().entrySet()) {
            Path f = dir.resolve(e.getKey());
            if (!Files.isRegularFile(f)) return Optional.of(dir + " has no " + e.getKey());
            String actual = sha256(f);
            if (!actual.equalsIgnoreCase(e.getValue())) {
                return Optional.of(f + " is not the validated " + pinned.name() + " file (SHA-256 " + actual.substring(0, 12)
                        + "..., expected " + e.getValue().substring(0, 12) + "...)");
            }
        }
        return Optional.empty();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
