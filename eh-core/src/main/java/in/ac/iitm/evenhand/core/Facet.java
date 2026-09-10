package in.ac.iitm.evenhand.core;

/**
 * The three facets of the measurement model. A fourth, the marking session, is
 * carried on {@link DesignCell#assessment()} rather than as a facet of its own,
 * because whether sessions may be pooled is something we test rather than assume.
 */
public enum Facet {
    STUDENT,
    QUESTION,
    GRADER
}
