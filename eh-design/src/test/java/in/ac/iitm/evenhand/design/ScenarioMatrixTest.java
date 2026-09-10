package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim that this platform works on an arbitrary course, tested rather than
 * asserted.
 *
 * <p>Each case below is an arrangement whose right answer was worked out on paper
 * before the audit was written, including the ones where the right answer is a
 * refusal. A platform that only ever says yes is not an audit, so the refusals carry
 * as much weight here as the successes.
 *
 * <p>Nothing in these tests supplies a mark. The audit's verdict is a function of the
 * marking arrangement alone, which is what lets an instructor be told what his data
 * can support before he hands any of it over.
 */
class ScenarioMatrixTest {

    // ---------------------------------------------------------------- identified

    @Test
    @DisplayName("question-wise with a few scripts double-marked: identified outright")
    void questionWiseWithDoubleMarkingIsEstimable() {
        MarkingDesign design = DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // This is the arrangement of the course the project was built for. Everything
        // turns on whether the small overlap set is enough, and it is: the second
        // marker on each question breaks the tie between that question's difficulty
        // and its marker's severity.
        assertThat(verdict).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(verdict.certificate().excessNullity()).isZero();
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH008_DOUBLE_MARK_FOUND);
    }

    @Test
    @DisplayName("question-wise with the marker rotated across roll-number blocks: identified, and free")
    void rotatingCrossedIsEstimableWithoutAnySecondMarking() {
        MarkingDesign design = DesignGenerator.rotatingCrossed(60, 5, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // Worth more than it looks. Nobody marks a single extra script, yet every
        // severity difference becomes estimable, because rotation crosses assistants
        // against both students and questions. This is the recommendation the linking
        // design should make before it asks anyone to mark anything twice.
        assertThat(verdict).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(verdict.certificate().excessNullity()).isZero();
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH005_NO_DOUBLE_MARKING);
    }

    // ------------------------------------------------- identified, but on an assumption

    @Test
    @DisplayName("script-wise, random piles: estimable only if the piles are assumed alike")
    void scriptWiseRandomRestsOnExchangeability() {
        MarkingDesign design = DesignGenerator.scriptWiseRandom(60, 5, 4, 42L);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // The design alone does not identify severity: each student is seen by exactly
        // one assistant, so that assistant's severity is absorbed into their students'
        // abilities. What rescues it is the assumption random allocation is for. The
        // platform says so instead of quietly reporting a number.
        assertThat(verdict).isInstanceOf(AuditVerdict.EstimableUnderExchangeability.class);
        assertThat(verdict.certificate().excessNullity()).isEqualTo(3); // graders - 1
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY);
    }

    @Test
    @DisplayName("a fixed roll-number split is the same design as a random one, and is reported as such")
    void fixedRollNumberSplitIsIndistinguishableByRankButVisibleInTheAllocation() {
        MarkingDesign random = DesignGenerator.scriptWiseRandom(60, 5, 4, 42L);
        MarkingDesign byRollNumber = DesignGenerator.scriptWiseFixedBlocks(60, 5, 4);

        AuditVerdict randomVerdict = IdentifiabilityAudit.audit(random);
        AuditVerdict rollNumberVerdict = IdentifiabilityAudit.audit(byRollNumber);

        // A limitation stated honestly rather than hidden: the two arrangements have
        // identical rank, so identifiability cannot distinguish them. The difference is
        // entirely in whether the assumption holds, and only the shape of the piles
        // speaks to that. The tool reports the shape and leaves the judgement to the
        // reader.
        assertThat(rollNumberVerdict).isInstanceOf(AuditVerdict.EstimableUnderExchangeability.class);
        assertThat(rollNumberVerdict.certificate().excessNullity())
                .isEqualTo(randomVerdict.certificate().excessNullity());

        String evidence = ((AuditVerdict.EstimableUnderExchangeability) rollNumberVerdict)
                .allocationEvidence();
        assertThat(evidence).contains("contiguous block of roll numbers");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("question-wise with no overlap: refused, with the reason and the repair named")
    void pureQuestionWiseIsRefused() {
        MarkingDesign design = DesignGenerator.questionWise(60, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // The behaviour that makes the platform trustworthy on a course it has never
        // seen. Five assistants, one question each, sixty students in common, and not
        // one severity difference is estimable; student overlap does not help and the
        // audit does not pretend it does.
        assertThat(verdict).isInstanceOf(AuditVerdict.NotEstimable.class);
        assertThat(verdict.permitsEstimation()).isFalse();
        assertThat(verdict.certificate().excessNullity()).isEqualTo(4); // graders - 1
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION);

        // A refusal that does not say what would fix it is just a failure.
        assertThat(verdict.diagnostics())
                .anySatisfy(d -> assertThat(d.fixIt()).isPresent());
    }

    @Test
    @DisplayName("one marker: refused, because there is no severity to be about")
    void singleMarkerIsRefused() {
        MarkingDesign design = DesignGenerator.singleMarker(60, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        assertThat(verdict).isInstanceOf(AuditVerdict.NotEstimable.class);
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH001_SINGLE_MARKER);
    }

    // ---------------------------------------------------------------- partial

    @Test
    @DisplayName("two disconnected islands: neither is comparable with the other")
    void disconnectedIslandsAreNotSilentlyPutOnOneScale() {
        MarkingDesign design = DesignGenerator.disconnectedIslands(30, 3, 2);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // The failure mode that matters most, because it is the one a tool could get
        // away with: reporting four severities on one scale when only pairs of them
        // are comparable would look perfectly plausible and be wrong. The right answer
        // is not a refusal - two of the four assistants genuinely are comparable - but
        // a partial verdict that names which two, and which two it cannot reach.
        assertThat(verdict).isInstanceOf(AuditVerdict.PartiallyEstimable.class);
        AuditVerdict.PartiallyEstimable partial = (AuditVerdict.PartiallyEstimable) verdict;
        assertThat(partial.estimableGraders()).hasSize(2);
        assertThat(partial.blockedGraders()).hasSize(2);
        assertThat(partial.estimableGraders()).doesNotContainAnyElementsOf(partial.blockedGraders());
        assertThat(verdict.certificate().excessNullity()).isGreaterThan(0);
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH002_DISCONNECTED_GRADERS);
    }

    @Test
    @DisplayName("one bridging double-mark links two islands, and the audit notices")
    void oneBridgeImprovesTheVerdict() {
        MarkingDesign without = DesignGenerator.disconnectedIslands(30, 3, 2);
        MarkingDesign with = DesignGenerator.disconnectedIslandsWithOneBridge(30, 3, 2);

        AuditVerdict before = IdentifiabilityAudit.audit(without);
        AuditVerdict after = IdentifiabilityAudit.audit(with);

        // A single script marked a second time buys exactly one dimension back. This is
        // the quantitative basis for the linking design: repairs are countable, so the
        // smallest sufficient set is a thing that can be computed rather than guessed.
        assertThat(after.certificate().excessNullity())
                .isLessThan(before.certificate().excessNullity());
    }

    // ---------------------------------------------------------------- invariants

    @Test
    @DisplayName("a verdict is evidence about one arrangement and cannot be moved to another")
    void certificateIsBoundToItsDesign() {
        MarkingDesign audited = DesignGenerator.rotatingCrossed(60, 5, 5, 5);
        MarkingDesign other = DesignGenerator.questionWise(60, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(audited);

        assertThat(verdict.certificate().describes(audited)).isTrue();
        assertThat(verdict.certificate().describes(other)).isFalse();
    }

    @Test
    @DisplayName("the audit reads no marks: the same design always gives the same verdict")
    void verdictDependsOnlyOnTheArrangement() {
        MarkingDesign a = DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8);
        MarkingDesign b = DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8);

        assertThat(IdentifiabilityAudit.audit(a).getClass())
                .isEqualTo(IdentifiabilityAudit.audit(b).getClass());
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    private static java.util.List<Diagnostic.Code> codes(AuditVerdict verdict) {
        return verdict.diagnostics().stream().map(Diagnostic::code).toList();
    }
}
