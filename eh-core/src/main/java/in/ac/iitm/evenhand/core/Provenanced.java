package in.ac.iitm.evenhand.core;

import java.time.Instant;
import java.util.Optional;

/**
 * A configuration value together with where it came from.
 *
 * <p>Nothing about a course is compiled in, so every course-shaped fact is either
 * inferred from the data, asserted by the instructor, or defaulted. The report says
 * which, for every input it used, so a conclusion someone disputes can be traced to
 * the assumption underneath it.
 *
 * @param value      the value in force
 * @param provenance how it was arrived at
 * @param evidence   for DETECTED values, what the profiler saw; for DECLARED, who said so
 * @param recordedAt when the value was set
 */
public record Provenanced<T>(T value, Provenance provenance, Optional<String> evidence, Instant recordedAt) {

    public static <T> Provenanced<T> detected(T value, String evidence) {
        return new Provenanced<>(value, Provenance.DETECTED, Optional.of(evidence), Instant.now());
    }

    public static <T> Provenanced<T> declared(T value, String by) {
        return new Provenanced<>(value, Provenance.DECLARED, Optional.of(by), Instant.now());
    }

    public static <T> Provenanced<T> defaulted(T value) {
        return new Provenanced<>(value, Provenance.DEFAULTED, Optional.empty(), Instant.now());
    }

    /** An instructor override always wins over a detected value, and is recorded as such. */
    public Provenanced<T> overriddenWith(T newValue, String by) {
        return declared(newValue, by);
    }

    public String describe(String fieldName) {
        return switch (provenance) {
            case DETECTED -> fieldName + " = " + value + " (detected: " + evidence.orElse("") + ")";
            case DECLARED -> fieldName + " = " + value + " (declared by " + evidence.orElse("instructor") + ")";
            case DEFAULTED -> fieldName + " = " + value + " (default; not confirmed)";
        };
    }
}
