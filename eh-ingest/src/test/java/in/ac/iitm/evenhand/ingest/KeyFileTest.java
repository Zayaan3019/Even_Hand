package in.ac.iitm.evenhand.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one file that maps pseudonyms back to people is never written in plain text.
 */
class KeyFileTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();
    private static final String CONTENTS = """
            # Even Hand pseudonymisation key.
            salt,00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
            identifier,label,digest
            CE23B092,Student 1,0a1b2c3d4e5f60718293a4b5c6d7e8f9
            Ananya Krishnan,Assistant 1,f9e8d7c6b5a4938271605f4e3d2c1b0a
            """;

    @Test
    @DisplayName("a sealed key opens again under the same passphrase, unchanged")
    void roundTrip() throws KeyFile.KeyFileException {
        byte[] sealed = KeyFile.seal(CONTENTS, PASSPHRASE);

        assertThat(KeyFile.open(sealed, PASSPHRASE)).isEqualTo(CONTENTS);
    }

    @Test
    @DisplayName("the sealed bytes contain no roll number and no name")
    void sealedBytesHoldNoIdentifier() {
        String asText = new String(KeyFile.seal(CONTENTS, PASSPHRASE), StandardCharsets.ISO_8859_1);

        assertThat(asText).doesNotContain("CE23B092", "Ananya", "Krishnan", "salt,");
    }

    @Test
    @DisplayName("a wrong passphrase is refused rather than yielding garbage")
    void wrongPassphraseIsRefused() {
        byte[] sealed = KeyFile.seal(CONTENTS, PASSPHRASE);

        assertThatThrownBy(() -> KeyFile.open(sealed, "not the passphrase at all".toCharArray()))
                .isInstanceOf(KeyFile.KeyFileException.class)
                .hasMessageContaining("wrong passphrase");
    }

    @Test
    @DisplayName("altering any part of the file, header included, stops it opening")
    void tamperingIsDetected() {
        byte[] sealed = KeyFile.seal(CONTENTS, PASSPHRASE);
        byte[] alteredBody = sealed.clone();
        alteredBody[alteredBody.length - 1] ^= 0x01;
        byte[] alteredSalt = sealed.clone();
        alteredSalt[12] ^= 0x01;   // inside the key-derivation salt

        assertThatThrownBy(() -> KeyFile.open(alteredBody, PASSPHRASE))
                .isInstanceOf(KeyFile.KeyFileException.class);
        assertThatThrownBy(() -> KeyFile.open(alteredSalt, PASSPHRASE))
                .isInstanceOf(KeyFile.KeyFileException.class);
    }

    @Test
    @DisplayName("a short passphrase is refused before anything is sealed with it")
    void shortPassphraseIsRefused() {
        assertThatThrownBy(() -> KeyFile.seal(CONTENTS, "short".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    @Test
    @DisplayName("sealing twice gives different bytes, so two sealed keys cannot be matched by eye")
    void sealingIsRandomised() {
        assertThat(KeyFile.seal(CONTENTS, PASSPHRASE)).isNotEqualTo(KeyFile.seal(CONTENTS, PASSPHRASE));
    }
}
