package in.ac.iitm.evenhand.embed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Not a test: single-thread forward-pass timing, to separate compute from threading. */
public final class ForwardProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Embedders.searchPath().stream().filter(d -> Files.isRegularFile(d.resolve("model.safetensors")))
                .findFirst().orElseThrow();
        BertEncoder enc = BertEncoder.load(dir.resolve("config.json"), dir.resolve("model.safetensors"));
        int[] ids = new int[57];
        for (int i = 0; i < ids.length; i++) ids[i] = 1000 + i;
        System.out.println("cores " + Runtime.getRuntime().availableProcessors());
        for (int round = 0; round < 6; round++) {
            long t = System.nanoTime();
            int reps = 10;
            for (int r = 0; r < reps; r++) enc.forward(ids);
            double ms = (System.nanoTime() - t) / 1e6 / reps;
            double gflops = 2.0 * 6 * 57 * (4 * 384 * 384 + 2 * 384 * 1536) / (ms / 1e3) / 1e9;
            System.out.printf(Locale.ROOT, "round %d: %.1f ms per answer, %.2f GFLOP/s%n", round, ms, gflops);
        }
    }
}
