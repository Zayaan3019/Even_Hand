package in.ac.iitm.evenhand.ingest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Replaces real identifiers with pseudonyms, in memory, before anything is written.
 *
 * <p>Each identifier becomes the HMAC-SHA-256 digest of its bytes under a secret
 * 256-bit salt, truncated to 128 bits, and is shown as a numbered label. Only the
 * digest and the label leave this object; the mapping back to the real identifier is
 * the instructor's key file, which is sealed before it is written (see {@link KeyFile}).
 *
 * <p><strong>Labels are numbered in digest order, not in order of appearance.</strong>
 * Numbering by first appearance would make "Student 1" the first row of the export,
 * which in a roll-sorted file is the lowest roll number: anyone with the class list
 * could undo every pseudonym by counting. Digest order is a keyed shuffle, so a label
 * says nothing about where a student sits in any list without the salt.
 *
 * <p>Why HMAC and not a plain hash: the identifier space is small and structured. There
 * are perhaps ten thousand live roll numbers at this institute and they follow an
 * obvious pattern, so an unsalted digest of one could be reversed by generating every
 * candidate and comparing. A secret salt makes that infeasible for anyone who does not
 * hold the key file.
 *
 * <p>The salt belongs to a course. A fresh one is drawn for its first run and kept in the
 * key file, and restoring it with {@link #fromKeyFile} makes later runs of the same course
 * &mdash; the quizzes, then the end-semester exam &mdash; produce the same digests. A new
 * offering draws a new salt, so nobody can follow an assistant or a student from one
 * offering's reports to the next without both keys.
 */
public final class Pseudonymiser {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int DIGEST_BYTES = 16; // 128 bits, ample against collision here
    private static final String SALT_LINE = "salt,";

    private final byte[] salt;
    private final Mac mac;
    /** raw identifier -> assigned label, in label order. */
    private final Map<String, String> labels = new LinkedHashMap<>();
    /** raw identifier -> digest. Together with the labels, the key the instructor keeps. */
    private final Map<String, String> digests = new LinkedHashMap<>();
    private final Set<String> prefixesIssued = new HashSet<>();

    private Pseudonymiser(byte[] salt) {
        this.salt = salt.clone();
        try {
            this.mac = Mac.getInstance(ALGORITHM);
            this.mac.init(new SecretKeySpec(this.salt, ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is required and is unavailable", e);
        }
    }

    /** A pseudonymiser under a fresh random salt, for a course's first run. */
    public static Pseudonymiser withRandomSalt() {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return new Pseudonymiser(salt);
    }

    /**
     * A pseudonymiser under a caller-supplied salt. For tests; in production the salt
     * comes from {@link #withRandomSalt()} or from the course's key file.
     */
    public static Pseudonymiser withSalt(byte[] salt) {
        if (salt.length < 16) {
            throw new IllegalArgumentException("salt must be at least 128 bits");
        }
        return new Pseudonymiser(salt);
    }

    /**
     * Restores the course's salt from its key file (already opened with {@link KeyFile#open}),
     * so this run's digests match the earlier runs of the same course.
     */
    public static Pseudonymiser fromKeyFile(String keyFileContents) {
        for (String line : keyFileContents.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(SALT_LINE)) {
                return withSalt(HexFormat.of().parseHex(trimmed.substring(SALT_LINE.length())));
            }
        }
        throw new IllegalArgumentException("the key file has no salt line, so earlier runs cannot be linked");
    }

    /**
     * Assigns labels to a set of identifiers, numbered in the order of their keyed digests.
     *
     * <p>All identifiers sharing a prefix must be labelled in one call: labelling more
     * later would renumber the earlier ones, so a second call for the same prefix is an
     * error rather than a silent change.
     *
     * @param rawIdentifiers the real identifiers; duplicates are fine, blanks are not
     * @param prefix         how the facet is named in reports, e.g. {@code "Student "}
     * @return raw identifier to label, in label order; the map never leaves ingest
     */
    public Map<String, String> assignLabels(Collection<String> rawIdentifiers, String prefix) {
        if (!prefixesIssued.add(prefix)) {
            throw new IllegalStateException("labels with prefix '" + prefix
                    + "' were already issued in this run; issuing more would renumber them");
        }
        TreeMap<String, String> byDigest = new TreeMap<>();
        for (String raw : rawIdentifiers) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("identifier is blank");
            }
            if (labels.containsKey(trimmed)) {
                throw new IllegalArgumentException(
                        "the same identifier is used for two kinds of participant; they must be distinct");
            }
            String previous = byDigest.put(digest(trimmed), trimmed);
            if (previous != null && !previous.equals(trimmed)) {
                throw new IllegalStateException("two identifiers share a 128-bit digest; refusing to merge them");
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        int index = 1;
        for (Map.Entry<String, String> e : byDigest.entrySet()) {
            String label = prefix + index++;
            labels.put(e.getValue(), label);
            digests.put(e.getValue(), e.getKey());
            out.put(e.getValue(), label);
        }
        return Collections.unmodifiableMap(out);
    }

    /** The digest that is safe to persist. Computing it does not assign a label. */
    public String digestFor(String raw) {
        return digest(raw.strip());
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
     * The contents of the instructor's key file: the course salt, then each real
     * identifier with its label and digest.
     *
     * <p>This is the only place a real identifier appears in any output of this package.
     * It is plain text in memory only; {@link KeyFile#seal} encrypts it before it is
     * written, and nothing downstream of ingest ever asks for it.
     */
    public String keyFileContents() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Even Hand pseudonymisation key. The instructor holds this file, sealed.\n");
        sb.append("# Without it, no output of this tool can be traced to a person.\n");
        sb.append(SALT_LINE).append(HexFormat.of().formatHex(salt)).append('\n');
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
