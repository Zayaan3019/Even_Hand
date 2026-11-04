package in.ac.iitm.evenhand.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything downstream is numbered and seeded by the pseudonyms. On real data, renaming a column once moved a
 * TA's severity by 1.2 points and changed 21 letter grades, with no change to anything the file said.
 */
class ContentSaltTest {

    private static final String FILE = """
            roll_no,assessment,question,max_marks,marks,ta,answer
            CE23B001,Quiz,Q1,10,7,Ravi,A beam carries load in bending
            CE23B002,Quiz,Q1,10,5,Meena,"Bending, mostly"
            CE23B003,Quiz,Q1,10,9,Ravi,The beam bends under load
            CE23B004,Quiz,Q1,10,6,Meena,It deflects
            """;

    private static String salt(String csv) throws IOException {
        return CsvIngest.pseudonymiserFor(csv).saltFingerprint();
    }

    @Test
    @DisplayName("renaming a column changes nothing the file says, so it changes no pseudonym")
    void columnNames() throws IOException {
        String renamed = "Roll No,Assessment,Question,Max Marks,Marks,Marker,Answer" + FILE.substring(FILE.indexOf('\n'));

        assertThat(salt(renamed)).isEqualTo(salt(FILE));
    }

    @Test
    @DisplayName("a byte-order mark and Windows line endings change no pseudonym")
    void lineEndings() throws IOException {
        assertThat(salt("﻿" + FILE.replace("\n", "\r\n"))).isEqualTo(salt(FILE));
    }

    @Test
    @DisplayName("re-sorting the rows changes no pseudonym")
    void rowOrder() throws IOException {
        String[] lines = FILE.split("\n");
        String resorted = String.join("\n", lines[0], lines[3], lines[1], lines[4], lines[2]) + "\n";

        assertThat(salt(resorted)).isEqualTo(salt(FILE));
    }

    @Test
    @DisplayName("writing a mark as 7.0 rather than 7 changes no pseudonym")
    void markFormat() throws IOException {
        assertThat(salt(FILE.replace(",10,7,", ",10,7.0,"))).isEqualTo(salt(FILE));
    }

    @Test
    @DisplayName("a corrected mark is a different file, so it gets a different salt")
    void correctedMark() throws IOException {
        assertThat(salt(FILE.replace(",10,7,", ",10,8,"))).isNotEqualTo(salt(FILE));
    }

    @Test
    @DisplayName("a changed answer is a different file, so it gets a different salt")
    void changedAnswer() throws IOException {
        assertThat(salt(FILE.replace("It deflects", "It sags"))).isNotEqualTo(salt(FILE));
    }
}
