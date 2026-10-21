package in.ac.iitm.evenhand.ingest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Pseudonym to real identifier, read from the key held in memory for one run. Used only where
 * the instructor is looking: their terminal and their own browser tab.
 */
public record PseudonymKey(Map<String, String> realByLabel) {

    public PseudonymKey {
        realByLabel = Map.copyOf(realByLabel);
    }

    /** Parses {@link CsvIngest.IngestResult#keyFileContents()}. */
    public static PseudonymKey of(String keyFileContents) {
        Map<String, String> map = new HashMap<>();
        boolean inTable = false;
        for (String line : keyFileContents.split("\n")) {
            if (line.startsWith("identifier,label,digest")) {
                inTable = true;
                continue;
            }
            if (!inTable || line.isBlank()) continue;
            String[] parts = line.split(",");
            if (parts.length >= 3) {
                map.put(parts[parts.length - 2], String.join(",", Arrays.copyOf(parts, parts.length - 2)));
            }
        }
        return new PseudonymKey(map);
    }

    /** The real identifier behind a pseudonym, or "?" if this key does not know it. */
    public String real(String label) {
        return realByLabel.getOrDefault(label, "?");
    }
}
