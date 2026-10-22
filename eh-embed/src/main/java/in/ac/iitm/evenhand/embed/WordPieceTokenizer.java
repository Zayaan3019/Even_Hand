package in.ac.iitm.evenhand.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT's tokenizer, written out: the "basic" pass (clean, lower-case, strip accents, split on
 * whitespace and punctuation, space out CJK characters) followed by WordPiece (greedy
 * longest-match-first against the vocabulary, continuation pieces prefixed {@code ##}).
 *
 * <p>It must reproduce Hugging Face's {@code BertTokenizer} token for token, or the model sees
 * text it was never trained on; {@code TransformerEmbedderTest} checks the ids against ones the
 * reference implementation produced.
 */
public final class WordPieceTokenizer {

    static final String CLS = "[CLS]", SEP = "[SEP]", UNK = "[UNK]";
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final boolean lowerCase;
    private final int cls, sep, unk;

    WordPieceTokenizer(Map<String, Integer> vocab, boolean lowerCase) {
        this.vocab = Map.copyOf(vocab);
        this.lowerCase = lowerCase;
        this.cls = id(CLS);
        this.sep = id(SEP);
        this.unk = id(UNK);
    }

    /** One token per line, the line number being its id: the {@code vocab.txt} format. */
    static WordPieceTokenizer fromVocabFile(Path vocabTxt, boolean lowerCase) throws IOException {
        Map<String, Integer> vocab = new HashMap<>();
        List<String> lines = Files.readAllLines(vocabTxt, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) vocab.putIfAbsent(lines.get(i), i);
        return new WordPieceTokenizer(vocab, lowerCase);
    }

    /** {@code [CLS] pieces... [SEP]}, truncated so the whole sequence is at most {@code maxLength}. */
    public int[] encode(String text, int maxLength) {
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        for (String word : basicTokens(text)) {
            for (int piece : wordPiece(word)) {
                if (ids.size() >= maxLength - 1) break;
                ids.add(piece);
            }
        }
        ids.add(sep);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** How many word pieces the text has before truncation, so long answers can be reported. */
    public int pieceCount(String text) {
        int n = 0;
        for (String word : basicTokens(text)) n += wordPiece(word).size();
        return n;
    }

    List<String> basicTokens(String text) {
        StringBuilder cleaned = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) return;
            if (isWhitespace(cp)) {
                cleaned.append(' ');
            } else if (isCjk(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        });
        List<String> out = new ArrayList<>();
        for (String token : cleaned.toString().strip().split(" +")) {
            if (token.isEmpty()) continue;
            String t = token;
            if (lowerCase) {
                t = stripAccents(t.toLowerCase(java.util.Locale.ROOT));
            }
            splitOnPunctuation(t, out);
        }
        return out;
    }

    List<Integer> wordPiece(String word) {
        if (word.codePointCount(0, word.length()) > MAX_CHARS_PER_WORD) return List.of(unk);
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = (start > 0 ? "##" : "") + word.substring(start, end);
                found = vocab.get(sub);
                if (found != null) break;
                end = Character.isLowSurrogate(word.charAt(end - 1)) && end - 2 > start ? end - 2 : end - 1;
            }
            if (found == null) return List.of(unk);
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    int id(String token) {
        Integer id = vocab.get(token);
        if (id == null) throw new IllegalArgumentException("the vocabulary has no " + token + " token");
        return id;
    }

    private static void splitOnPunctuation(String token, List<String> out) {
        StringBuilder current = new StringBuilder();
        token.codePoints().forEach(cp -> {
            if (isPunctuation(cp)) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                out.add(new String(Character.toChars(cp)));
            } else {
                current.appendCodePoint(cp);
            }
        });
        if (!current.isEmpty()) out.add(current.toString());
    }

    private static String stripAccents(String s) {
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(nfd.length());
        nfd.codePoints().forEach(cp -> {
            if (Character.getType(cp) != Character.NON_SPACING_MARK) b.appendCodePoint(cp);
        });
        return b.toString();
    }

    private static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE
                || t == Character.SURROGATE || t == Character.UNASSIGNED;
    }

    /** ASCII symbols count as punctuation, as in BERT, as well as every Unicode P* category. */
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION
                || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
                || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
