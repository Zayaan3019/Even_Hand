package in.ac.iitm.evenhand.core;

/**
 * Detected from the observed score categories. Decides whether the model needs
 * threshold parameters at all.
 */
public enum ScoreType {
    /** Every item scored 0 or 1. */
    BINARY,
    /** Integer part-marks up to an item maximum. */
    PARTIAL_CREDIT,
    /** Ordered categories that are not counts of marks. */
    ORDINAL
}
