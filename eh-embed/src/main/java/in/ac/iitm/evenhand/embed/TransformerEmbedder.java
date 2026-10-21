package in.ac.iitm.evenhand.embed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * A sentence-transformer model from a local folder in the Hugging Face layout:
 *
 * <pre>
 *   config.json  vocab.txt  model.safetensors  [tokenizer_config.json] [sentence_bert_config.json]
 *   [1_Pooling/config.json]
 * </pre>
 *
 * <p>Tokenise, run the encoder, pool (mean over tokens, or the first token, as the model's
 * pooling config says), and scale to unit length, which is what {@code SentenceTransformer.encode}
 * does with {@code normalize_embeddings=True}. Answers are encoded in parallel, one per core,
 * and remembered by their text, so a course where many students wrote the same sentence pays
 * for it once.
 */
public final class TransformerEmbedder implements SentenceEmbedder {

    private final String id;
    private final WordPieceTokenizer tokenizer;
    private final BertEncoder encoder;
    private final boolean clsPooling;
    private final int maxLength;
    /** Answers remembered, at most; a long-running page server forgets them all past this. */
    private static final int MEMO_LIMIT = 200_000;

    private final Map<String, float[]> memo = new ConcurrentHashMap<>();

    private TransformerEmbedder(String id, WordPieceTokenizer tokenizer, BertEncoder encoder,
                                boolean clsPooling, int maxLength) {
        this.id = id;
        this.tokenizer = tokenizer;
        this.encoder = encoder;
        this.clsPooling = clsPooling;
        this.maxLength = maxLength;
    }

    /** Loads the model in {@code dir}; {@code id} names it in reports. */
    public static TransformerEmbedder load(Path dir, String id) throws IOException {
        for (String required : List.of("config.json", "vocab.txt", "model.safetensors")) {
            if (!Files.isRegularFile(dir.resolve(required))) {
                throw new IOException("the model folder " + dir + " has no " + required);
            }
        }
        boolean lower = true;
        Path tokCfg = dir.resolve("tokenizer_config.json");
        if (Files.isRegularFile(tokCfg)) {
            Object v = Json.object(Files.readString(tokCfg)).get("do_lower_case");
            if (v instanceof Boolean b) lower = b;
        }
        int maxLength = 512;
        Path stCfg = dir.resolve("sentence_bert_config.json");
        if (Files.isRegularFile(stCfg)) {
            Object v = Json.object(Files.readString(stCfg)).get("max_seq_length");
            if (v instanceof Double d) maxLength = d.intValue();
        }
        boolean cls = false;
        Path pooling = dir.resolve("1_Pooling").resolve("config.json");
        if (Files.isRegularFile(pooling)) {
            Map<String, Object> p = Json.object(Files.readString(pooling));
            cls = Boolean.TRUE.equals(p.get("pooling_mode_cls_token"));
            if (!cls && !Boolean.TRUE.equals(p.get("pooling_mode_mean_tokens"))) {
                throw new IOException("only mean or CLS pooling is supported; see " + pooling);
            }
        }
        BertEncoder encoder = BertEncoder.load(dir.resolve("config.json"), dir.resolve("model.safetensors"));
        maxLength = Math.min(maxLength, encoder.maxPositions);
        return new TransformerEmbedder(id, WordPieceTokenizer.fromVocabFile(dir.resolve("vocab.txt"), lower),
                encoder, cls, maxLength);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean semantic() {
        return true;
    }

    /** Word pieces the model reads at most; longer answers are cut there, as in the reference. */
    public int maxLength() {
        return maxLength;
    }

    public WordPieceTokenizer tokenizer() {
        return tokenizer;
    }

    @Override
    public float[][] embed(List<String> texts) {
        if (memo.size() > MEMO_LIMIT) memo.clear();
        float[][] out = new float[texts.size()][];
        IntStream.range(0, texts.size()).parallel().forEach(i ->
                out[i] = memo.computeIfAbsent(texts.get(i), this::embedOne).clone());
        return out;
    }

    private float[] embedOne(String text) {
        int[] ids = tokenizer.encode(text, maxLength);
        float[] states = encoder.forward(ids);
        int h = encoder.hidden;
        float[] v = new float[h];
        if (clsPooling) {
            System.arraycopy(states, 0, v, 0, h);
        } else {
            double[] sum = new double[h];
            for (int t = 0; t < ids.length; t++) for (int c = 0; c < h; c++) sum[c] += states[t * h + c];
            for (int c = 0; c < h; c++) v[c] = (float) (sum[c] / ids.length);
        }
        return SentenceEmbedder.normalise(v);
    }
}
