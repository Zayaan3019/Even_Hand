package in.ac.iitm.evenhand.core;

/**
 * Where a value in the {@link CourseProfile} came from. Every report states this
 * for every input it used, so a disputed conclusion can be traced to the
 * assumption underneath it rather than argued about in the abstract.
 */
public enum Provenance {
    /** Inferred from the data by the profiler, with evidence. */
    DETECTED,
    /** Asserted by the instructor, who may override anything DETECTED. */
    DECLARED,
    /** Neither: a documented default was used. */
    DEFAULTED
}
