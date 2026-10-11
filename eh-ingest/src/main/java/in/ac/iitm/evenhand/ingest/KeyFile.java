package in.ac.iitm.evenhand.ingest;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Seals the instructor's key file so that it is never on disk in plain text.
 *
 * <p>The key file is the only table that maps pseudonyms back to roll numbers and
 * names, so it is the one file whose loss would matter. It is encrypted with AES-256
 * in GCM mode under a key derived from the instructor's passphrase by PBKDF2 with
 * HMAC-SHA-256. Everything used here ships with the JDK; nothing third-party touches
 * the key.
 *
 * <p>Layout: {@code "EHKEY1"}, the PBKDF2 iteration count (4 bytes), a 16-byte salt, a
 * 12-byte nonce, then the ciphertext with its 16-byte authentication tag. The header is
 * authenticated as associated data, so altering any byte of the file &mdash; including
 * the iteration count &mdash; makes it fail to open rather than open differently.
 */
public final class KeyFile {

    public static final int DEFAULT_ITERATIONS = 600_000;
    /** Files claiming fewer iterations than this are refused, so the header cannot be downgraded. */
    private static final int MIN_ITERATIONS = 100_000;
    public static final int MIN_PASSPHRASE_LENGTH = 12;

    private static final byte[] MAGIC = "EHKEY1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int HEADER_BYTES = MAGIC.length + 4 + SALT_BYTES + NONCE_BYTES;

    private KeyFile() {
    }

    /** The key file could not be opened: a wrong passphrase, or a file that has been altered. */
    public static final class KeyFileException extends Exception {
        public KeyFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Encrypts the key file's contents under the passphrase.
     *
     * @throws IllegalArgumentException when the passphrase is shorter than
     *                                  {@link #MIN_PASSPHRASE_LENGTH} characters
     */
    public static byte[] seal(String contents, char[] passphrase) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw new IllegalArgumentException("the passphrase must be at least " + MIN_PASSPHRASE_LENGTH
                    + " characters; it is all that stands between the key file and the roll numbers in it");
        }
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).putInt(DEFAULT_ITERATIONS).put(salt).put(nonce).array();
        byte[] key = deriveKey(passphrase, salt, DEFAULT_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(contents.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(header.length + ciphertext.length).put(header).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is required and is unavailable", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Decrypts a sealed key file. */
    public static String open(byte[] sealed, char[] passphrase) throws KeyFileException {
        if (sealed.length < HEADER_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC)) {
            throw new KeyFileException("this is not an Even Hand key file", null);
        }
        ByteBuffer buffer = ByteBuffer.wrap(sealed);
        buffer.position(MAGIC.length);
        int iterations = buffer.getInt();
        if (iterations < MIN_ITERATIONS) {
            throw new KeyFileException("the key file claims too few key-derivation iterations; refusing it", null);
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(salt).get(nonce);
        byte[] header = Arrays.copyOf(sealed, HEADER_BYTES);
        byte[] key = deriveKey(passphrase, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] plain = cipher.doFinal(sealed, HEADER_BYTES, sealed.length - HEADER_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new KeyFileException("wrong passphrase, or the key file has been altered", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is required and is unavailable", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 with HMAC-SHA-256 is required and is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
