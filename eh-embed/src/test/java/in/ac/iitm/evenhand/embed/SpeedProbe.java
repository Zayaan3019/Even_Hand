package in.ac.iitm.evenhand.embed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Not a test: measures answers embedded per second on this machine. */
public final class SpeedProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Embedders.searchPath().stream().filter(d -> Files.isRegularFile(d.resolve("model.safetensors")))
                .findFirst().orElseThrow();
        long t0 = System.nanoTime();
        TransformerEmbedder m = TransformerEmbedder.load(dir, "probe");
        System.out.printf(Locale.ROOT, "load %.2f s%n", (System.nanoTime() - t0) / 1e9);
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 400;
        for (int round = 0; round < 2; round++) {
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                texts.add("Answer " + round + "-" + i + ": the bending moment is maximum where the shear force changes "
                        + "sign, so for the simply supported beam under a central point load the maximum moment "
                        + "is PL/4 at midspan, and the deflection follows from double integration of M/EI.");
            }
            long t = System.nanoTime();
            m.embed(texts);
            double s = (System.nanoTime() - t) / 1e9;
            System.out.printf(Locale.ROOT, "round %d: %d answers (%d pieces each) in %.2f s = %.0f answers/s%n", round, n,
                    m.tokenizer().encode(texts.get(0), 256).length, s, n / s);
        }
    }
}
