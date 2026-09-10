package in.ac.iitm.evenhand.ingest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaces real identifiers with pseudonyms, in memory, before anything is written.
 *
 * <p>Roll numbers and assistants' names never reach disk. Each run generates a fresh
 * 256-bit salt; an identifier becomes the HMAC-SHA-256 digest of its bytes under that
 * salt, truncated to 128 bits, and is shown as a sequential label. Only the digest and
 * the label leave this object. The salt is handed to the caller once, to write into the
 * instructor's key file, and the application never reads that file back.
 *
 * <p>Why HMAC and not a plain hash: the identifier space is small and structured. There
 * are perhaps ten thousand live roll numbers at this institute and they follow an
 * obvious pattern, so an unsalted digest of one could be reversed by generating every
 * candidate and comparing. A per-run secret salt makes that infeasible for anyone who
 * does not hold the key file, which is the instructor alone.
 *
 * <p>A fresh salt per run also means two runs of the same course produce unrelated
 * pseudonyms. That is deliberate: it stops anyone linking an assistant across reports
 * without the key, at the cost of the instructor needing the key file to compare two
 * runs, which is the trade we want.
 */
public final class Pseudonymiser {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int DIGEST_BYTES = 16; // 128 bits, ample against collision here

    private final byte[] salt;
    private final Mac mac;
    /** raw identifier -> assigned label, in order of first appearance. */
    private final Map<String, String> labels = new LinkedHashMap<>();
    /** raw identifier -> digest. This is the key the instructor keeps. */
    private final Map<String, String> digests = new LinkedHashMap<>();
    private final Map<String, Integer> nextIndex = new LinkedHashMap<>();

    private Pseudonymiser(byte[] salt) {
        this.salt = salt.clone();
        try {
            this.mac = Mac.getInstance(ALGORITHM);
            this.mac.init(new SecretKeySpec(this.salt, ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is required and is unavailable", e);
        }
    }

    /** A pseudonymiser under a fresh random salt. The usual entry point. */
    public static Pseudonymiser withRandomSalt() {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return new Pseudonymiser(salt);
    }

    /**
     * A pseudonymiser under a caller-supplied salt.
     *
     * <p>For tests, and for the instructor re-running a previous analysis from his key
     * file. Never call this with a constant in production code.
     */
    public static Pseudonymiser withSalt(byte[] salt) {
        if (salt.length < 16) {
            throw new IllegalArgumentException("salt must be at least 128 bits");
        }
        return new Pseudonymiser(salt);
    }

    /**
     * The display label for an identifier, assigning one on first sight.
     *
     * @param raw    the real identifier, which does not escape this object
     * @param prefix how the facet is named in reports, e.g. {@code "Assistant "}
     */
    public String labelFor(String raw, String prefix) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("identifier is blank");
        }
        return labels.computeIfAbsent(trimmed, key -> {
            digests.put(key, digest(key));
            int index = nextIndex.merge(prefix, 1, Integer::sum);
            return prefix + index;
        });
    }

    /** The digest that is safe to persist. */
    public String digestFor(String raw) {
        String trimmed = raw.strip();
        labelFor(trimmed, "");
        return digests.get(trimmed);
    }

    private String digest(String raw) {
        byte[] full;
        synchronized (mac) {
            full = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
        }
        byte[] truncated = new byte[DIGEST_BYTES];
        System.arraycopy(full, 0, truncated, 0, DIGEST_BYTES);
        return HexFormat.of().formatHex(truncated);
    }

    /**
     * The contents of the instructor's key file: real identifier, its label, its digest.
     *
     * <p>This is the only place a real identifier appears in any output of this
     * package, and the caller is expected to write it somewhere the application does not
     * read. Nothing downstream of ingest ever asks for it.
     */
    public String keyFileContents() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Even Hand pseudonymisation key. The instructor holds this file.\n");
        sb.append("# The application never reads it back. Without it, no output of this\n");
        sb.append("# tool can be traced to a person.\n");
        sb.append("identifier,label,digest\n");
        for (Map.Entry<String, String> e : labels.entrySet()) {
            sb.append(escape(e.getKey())).append(',')
              .append(escape(e.getValue())).append(',')
              .append(digests.get(e.getKey())).append('\n');
        }
        return sb.toString();
    }

    /**
     * A short fingerprint of the salt, safe to record in a run manifest.
     *
     * <p>It lets two reports be checked for having come from the same key without the
     * manifest carrying anything that would let a reader reverse a pseudonym.
     */
    public String saltFingerprint() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(salt);
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required and is unavailable", e);
        }
    }

    public int distinctIdentifiers() {
        return labels.size();
    }

    private static String escape(String field) {
        return field.contains(",") || field.contains("\"")
                ? '"' + field.replace("\"", "\"\"") + '"'
                : field;
    }
}
