package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The claim that this platform works on an arbitrary course, tested rather than
 * asserted.
 *
 * <p>Each case below is an arrangement whose right answer was worked out on paper
 * before the audit was written, including the ones where the right answer is a
 * refusal. A platform that only ever says yes is not an audit, so the refusals carry
 * as much weight here as the successes. Each case now has three answers: whether
 * severity can be measured, whether it could have moved anyone, and (where the marking
 * order is known) whether drift can be measured.
 *
 * <p>Nothing in these tests supplies a mark. The audit's verdict is a function of the
 * marking arrangement alone, which is what lets an instructor be told what his data
 * can support before he hands any of it over.
 */
class ScenarioMatrixTest {

    // ---------------------------------------------------------------- identified

    @Test
    @DisplayName("question-wise with a few scripts double-marked: identified outright, and nobody exposed")
    void questionWiseWithDoubleMarkingIsEstimable() {
        MarkingDesign design = DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // This is the arrangement of the course the project was built for. The second
        // marker on each question breaks the tie between that question's difficulty and
        // its marker's severity, so severities are available as feedback. And because the
        // second marks do not count, every student's marks of record still came from one
        // assistant per question: nobody was exposed to a different mix.
        assertThat(verdict).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(verdict.certificate().excessNullity()).isZero();
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH008_DOUBLE_MARK_FOUND);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.NONE);
    }

    @Test
    @DisplayName("question-wise with the marker rotated across roll-number blocks: identified, and free")
    void rotatingCrossedIsEstimableWithoutAnySecondMarking() {
        MarkingDesign design = DesignGenerator.rotatingCrossed(60, 5, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // Nobody marks a single extra script, yet every severity difference becomes
        // estimable, because rotation crosses assistants against both students and
        // questions. With every question worth the same, each student meets each
        // assistant on equal marks, so nobody's mix differs either.
        assertThat(verdict).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(verdict.certificate().excessNullity()).isZero();
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH005_NO_DOUBLE_MARKING);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.NONE);
    }

    @Test
    @DisplayName("the same rotation over questions of unequal worth creates exposure that was not there")
    void rotationOverUnequalQuestionsCreatesExposure() {
        MarkingDesign design = DesignGenerator.rotatingCrossed(60, 5, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design, new double[] {5, 10, 15, 10, 10});

        // The reason rotation is offered for feedback rather than as a repair. A student in
        // the first block meets one assistant on the 5-mark question and another on the
        // 15-mark one; a student in the next block the reverse. Five of the paper's fifty
        // marks change hands relative to the class average. It is estimable, so it could be
        // corrected, but pure question-wise marking would not have created it at all.
        assertThat(verdict).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.ESTIMABLE);
        assertThat(verdict.exposure().maxSpread()).isCloseTo(5.0, within(1e-9));
        assertThat(verdict.exposure().meanTotal()).isCloseTo(50.0, within(1e-9));
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
        // platform says so instead of quietly reporting a number, and says the same of
        // the harm, which is real here: each student met one assistant only.
        assertThat(verdict).isInstanceOf(AuditVerdict.EstimableUnderExchangeability.class);
        assertThat(verdict.certificate().excessNullity()).isEqualTo(3); // graders - 1
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.UNDER_EXCHANGEABILITY);
        assertThat(((AuditVerdict.EstimableUnderExchangeability) verdict).allocationEvidence())
                .contains("interleaved");
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
    @DisplayName("question-wise with no overlap: refused, and said to be harmless rather than wrong")
    void pureQuestionWiseIsRefusedButHarmless() {
        MarkingDesign design = DesignGenerator.questionWise(60, 5, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // Five assistants, one question each, and not one severity difference is
        // estimable; student overlap does not help and the audit does not pretend it
        // does. But every student met the same assistant on every question, so no
        // severity can have moved anyone relative to anyone else. That is the finding,
        // and raising an error over it would send the instructor chasing nothing.
        assertThat(verdict).isInstanceOf(AuditVerdict.NotEstimable.class);
        assertThat(verdict.permitsEstimation()).isFalse();
        assertThat(verdict.certificate().excessNullity()).isEqualTo(4); // graders - 1
        assertThat(verdict.exposure().isNone()).isTrue();
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION);
        assertThat(verdict.diagnostics())
                .filteredOn(d -> d.code() == Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION)
                .allSatisfy(d -> {
                    assertThat(d.severity()).isEqualTo(Diagnostic.Severity.NOTE);
                    assertThat(d.message()).contains("cannot have moved anyone");
                });
        assertThat(verdict.diagnostics()).noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);

        // Something is still worth suggesting, for an instructor who wants feedback.
        assertThat(verdict.diagnostics()).anySatisfy(d -> assertThat(d.fixIt()).isPresent());
    }

    @Test
    @DisplayName("question-wise where one student skipped a question: the confound is an error again")
    void aSkippedQuestionMakesTheConfoundMatter() {
        MarkingDesign full = DesignGenerator.questionWise(60, 5, 5);
        List<DesignCell> cells = full.cells().stream()
                .filter(c -> !(c.student() == 0 && c.question() == 0))
                .toList();
        MarkingDesign design = MarkingDesign.of(cells,
                full.studentLabels(), full.questionLabels(), full.graderLabels());

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // One student was never marked by the Q1 assistant, so their mix of assistants
        // differs from everyone else's, and the Q1 assistant's severity - which cannot be
        // measured - moves everyone but them. Harmlessness is a property of the
        // allocation, and the audit re-derives it rather than assuming it for a shape.
        assertThat(verdict).isInstanceOf(AuditVerdict.NotEstimable.class);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.NOT_ESTIMABLE);
        assertThat(verdict.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION);
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.ERROR);
        });
    }

    @Test
    @DisplayName("one marker: refused, because there is no severity to be about")
    void singleMarkerIsRefused() {
        MarkingDesign design = DesignGenerator.singleMarker(60, 5);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        assertThat(verdict).isInstanceOf(AuditVerdict.NotEstimable.class);
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH001_SINGLE_MARKER);
        assertThat(verdict.exposure().isNone()).isTrue();
    }

    // ---------------------------------------------------------------- partial

    @Test
    @DisplayName("two disconnected islands: two comparable groups, neither called the main one")
    void disconnectedIslandsAreReportedAsTwoGroups() {
        MarkingDesign design = DesignGenerator.disconnectedIslands(30, 3, 2);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // The failure mode that matters most, because it is the one a tool could get
        // away with: reporting four severities on one scale when only pairs of them are
        // comparable would look plausible and be wrong. Nor is one pair "reachable" and
        // the other "blocked": the islands are the same size, and each is a scale of its
        // own. The harm across islands is real and cannot be measured.
        assertThat(verdict).isInstanceOf(AuditVerdict.PartiallyEstimable.class);
        AuditVerdict.PartiallyEstimable partial = (AuditVerdict.PartiallyEstimable) verdict;
        assertThat(partial.groups()).containsExactly(List.of(0, 1), List.of(2, 3));
        assertThat(verdict.headline()).contains("2 groups");
        assertThat(verdict.certificate().excessNullity()).isGreaterThan(0);
        assertThat(codes(verdict)).contains(Diagnostic.Code.EH002_DISCONNECTED_GRADERS);
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.NOT_ESTIMABLE);
    }

    @Test
    @DisplayName("mixed marking: severity only partly comparable, yet every harmful difference is estimable")
    void mixedMarkingCanBePartialButCorrectable() {
        MarkingDesign design = DesignGenerator.mixed(60, 6, 4, 7L);

        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        // Why exposure is asked separately. The two assistants who each own a whole
        // question cannot be compared with anyone, which sounds bad; but every student met
        // them on the same questions, so they moved nobody. Every difference in exposure
        // is between the two assistants who share scripts, and that is estimable.
        assertThat(verdict).isInstanceOf(AuditVerdict.PartiallyEstimable.class);
        assertThat(((AuditVerdict.PartiallyEstimable) verdict).groups()).contains(List.of(0, 1));
        assertThat(verdict.exposure().status()).isEqualTo(Exposure.Status.ESTIMABLE);
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
        assertThat(after).isInstanceOf(AuditVerdict.EstimableUnderExchangeability.class);
        assertThat(after.exposure().status()).isEqualTo(Exposure.Status.UNDER_EXCHANGEABILITY);
    }

    // ---------------------------------------------------------------- drift

    @Test
    @DisplayName("a shared stack order leaves drift resting on an assumption, and the fix is free")
    void sharedStackOrderLeavesDriftUnderAnAssumption() {
        DriftAudit.Result result = DriftAudit.audit(DesignGenerator.questionWiseWithStackOrder(60, 5, 5, false));

        // Every assistant went through the scripts in roll-number order, so a script's
        // position is the same on every question: position moves with the student, and
        // the same students are late every time.
        assertThat(result.status()).isEqualTo(DriftAudit.Status.UNDER_EXCHANGEABILITY);
        assertThat(result.diagnostic()).hasValueSatisfying(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH013_DRIFT_RESTS_ON_STACK_ORDER);
            assertThat(d.fixIt().orElseThrow()).contains("reverse order");
        });
    }

    @Test
    @DisplayName("reversing the stack for alternate markers identifies drift from the marking alone")
    void alternateReversalIdentifiesDrift() {
        DriftAudit.Result result = DriftAudit.audit(DesignGenerator.questionWiseWithStackOrder(60, 5, 5, true));

        assertThat(result.status()).isEqualTo(DriftAudit.Status.ESTIMABLE);
        assertThat(result.diagnostic()).isEmpty();
    }

    @Test
    @DisplayName("without a recorded marking order, drift is reported as untestable, not as absent")
    void noMarkingOrderMeansNoDriftVerdict() {
        assertThat(DriftAudit.audit(DesignGenerator.questionWise(60, 5, 5)).status())
                .isEqualTo(DriftAudit.Status.NOT_RECORDED);
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

    private static List<Diagnostic.Code> codes(AuditVerdict verdict) {
        return verdict.diagnostics().stream().map(Diagnostic::code).toList();
    }
}
