package in.ac.iitm.evenhand.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The pieces of the embedder that need no model file. */
class EmbedPartsTest {

    @Test
    @DisplayName("erf matches tabulated values to 1e-12 across its range")
    void erf() {
        double[][] table = { {0, 0}, {0.1, 0.1124629160182849}, {0.5, 0.5204998778130465},
                {1, 0.8427007929497149}, {2, 0.9953222650189527}, {2.5, 0.9995930479825550},
                {3, 0.9999779095030014}, {4, 0.9999999845827421}, {-1.5, -0.9661051464753108} };
        for (double[] row : table) assertThat(BertEncoder.erf(row[0])).isCloseTo(row[1], within(1e-12));
    }

    @Test
    @DisplayName("the basic tokenizer lower-cases, strips accents and splits punctuation as BERT does")
    void basicTokens() {
        WordPieceTokenizer t = new WordPieceTokenizer(Map.of("[CLS]", 0, "[SEP]", 1, "[UNK]", 2), true);
        assertThat(t.basicTokens("Café's  naïve—ANSWER!\n(σ=My/I)"))
                .containsExactly("cafe", "'", "s", "naive", "—", "answer", "!", "(", "σ", "=", "my", "/", "i", ")");
        assertThat(t.basicTokens("日本 ok")).containsExactly("日", "本", "ok");
    }

    @Test
    @DisplayName("WordPiece takes the longest piece first and gives up on a word with no cover")
    void wordPiece() {
        WordPieceTokenizer t = new WordPieceTokenizer(Map.of("[CLS]", 0, "[SEP]", 1, "[UNK]", 2,
                "un", 3, "##aff", 4, "##able", 5, "unaff", 6, "##a", 7), true);
        assertThat(t.wordPiece("unaffable")).containsExactly(6, 5);
        assertThat(t.wordPiece("unknownword")).containsExactly(2);
        assertThat(t.encode("unaffable unaffable unaffable", 4)).containsExactly(0, 6, 5, 1);
    }

    @Test
    @DisplayName("safetensors: reads F32 and F16, and refuses a truncated file")
    void safetensors(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("t.safetensors");
        String header = "{\"a\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]},"
                + "\"b\":{\"dtype\":\"F16\",\"shape\":[1],\"data_offsets\":[8,10]},\"__metadata__\":{\"format\":\"pt\"}}";
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + h.length + 10).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(h.length).put(h).putFloat(1.5f).putFloat(-2f).putShort(Float.floatToFloat16(0.25f));
        Files.write(f, buf.array());
        SafeTensors t = SafeTensors.open(f);
        assertThat(t.floats("a")).containsExactly(1.5f, -2f);
        assertThat(t.floats("b")).containsExactly(0.25f);
        assertThat(t.shape("a")).containsExactly(2);

        byte[] cut = java.util.Arrays.copyOf(buf.array(), buf.array().length - 4);
        Files.write(f, cut);
        assertThatThrownBy(() -> SafeTensors.open(f)).hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("JSON: nested objects, arrays, escapes and numbers")
    void json() {
        Map<String, Object> o = Json.object("{\"a\": [1, 2.5e1, -3], \"b\": {\"c\": \"x\\\"y\\u00e9\"}, \"d\": true, \"e\": null}");
        assertThat(o.get("a")).isEqualTo(List.of(1.0, 25.0, -3.0));
        assertThat(((Map<?, ?>) o.get("b")).get("c")).isEqualTo("x\"yé");
        assertThat(o.get("d")).isEqualTo(true);
        assertThat(o).containsKey("e");
        assertThatThrownBy(() -> Json.object("{\"a\": 1,}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lexical fallback: unit vectors; shared wording scores above unrelated wording")
    void lexical() {
        float[][] v = new LexicalEmbedder().embed(List.of(
                "The force in member BC is tensile.",
                "Member BC carries a tensile force.",
                "Turbulent flow has a high Reynolds number.",
                ""));
        double norm = 0;
        for (float x : v[0]) norm += x * x;
        assertThat(norm).isCloseTo(1.0, within(1e-5));
        assertThat(SentenceEmbedder.cosine(v[0], v[1])).isGreaterThan(SentenceEmbedder.cosine(v[0], v[2]) + 0.3);
        assertThat(v[3]).containsOnly(0f);
    }

    @Test
    @DisplayName("a model folder whose weights are not the pinned file is reported, not trusted")
    void pinning(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("model.safetensors"), "not the model");
        Files.writeString(dir.resolve("vocab.txt"), "x");
        Files.writeString(dir.resolve("config.json"), "{}");
        assertThat(Embedders.verify(dir, Embedders.MINILM)).get().asString().contains("not the validated");
    }
}
