package in.ac.iitm.evenhand.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pseudonyms derived from the input file: stable for one file, unrelated across files.
 */
class PseudonymiserTest {

    private static final List<String> ROLLS = List.of("CE23B001", "CE23B002", "CE23B003", "CE23B004", "CE23B005",
            "CE23B006", "CE23B007", "CE23B008", "CE23B009", "CE23B010", "CE23B011", "CE23B012");

    private static Map<String, String> labels(Pseudonymiser p) {
        return p.assignLabels(ROLLS, "Student ");
    }

    @Test
    @DisplayName("the same file always gets the same pseudonyms, so a re-run reproduces its report")
    void sameFileSameLabels() {
        String file = "roll_no,question,max_marks,marks,ta\nCE23B001,Q1,10,7,Ravi\n";

        assertThat(labels(Pseudonymiser.forInput(file))).isEqualTo(labels(Pseudonymiser.forInput(file)));
    }

    @Test
    @DisplayName("another file gets an unrelated numbering, so offerings cannot be linked by label")
    void otherFileOtherLabels() {
        Map<String, String> a = labels(Pseudonymiser.forInput("roll_no,marks\nCE23B001,7\n"));
        Map<String, String> b = labels(Pseudonymiser.forInput("roll_no,marks\nCE23B001,8\n"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("labels still follow the keyed digest, not the order of the file")
    void notInOrderOfAppearance() {
        Map<String, String> l = labels(Pseudonymiser.forInput("roll_no,marks\nCE23B001,7\n"));

        assertThat(ROLLS.stream().map(l::get).toList())
                .isNotEqualTo(ROLLS.stream().map(r -> "Student " + (ROLLS.indexOf(r) + 1)).toList());
    }
}
