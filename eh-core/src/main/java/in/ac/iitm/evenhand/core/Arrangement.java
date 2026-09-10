package in.ac.iitm.evenhand.core;

/**
 * How the marking was divided up. This is <em>detected</em> from the incidence
 * structure, never declared and never compiled in: no control flow in this
 * codebase branches on the value. It exists so the report can name the
 * arrangement in the instructor's own words.
 */
public enum Arrangement {

    /** Each grader marks every question for a subset of students. */
    SCRIPT_WISE,

    /** Each grader marks one question across the whole class. Raters are nested
     *  within items, so severity and question difficulty are confounded unless
     *  some question is marked by more than one grader. */
    QUESTION_WISE,

    /** Neither pattern dominates: some graders span questions, some do not. */
    MIXED,

    /** One grader marked everything. There is no severity to estimate. */
    SINGLE_MARKER,

    /** Not enough data to classify. */
    UNKNOWN
}
