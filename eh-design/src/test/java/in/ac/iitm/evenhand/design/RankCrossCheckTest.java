package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.numerics.ExactRowSpace;
import in.ac.iitm.evenhand.numerics.SymmetricEigen;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every verdict in the scenario matrix, reached a second way and then re-verified.
 *
 * <p>The audit decides its verdicts by exact integer elimination. These tests require the
 * same null dimension from the eigenvalues of the information matrix, computed in floating
 * point by an unrelated algorithm, on every scenario the design document reports. They
 * then check that each certificate the audit issues survives the verification the
 * estimator's entry point applies, which is also what ties the audit's column layout to
 * the verifier's.
 */
class RankCrossCheckTest {

    static Stream<AuditReport.Scenario> scenarios() {
        return AuditReport.scenarios().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void exactNullityAgreesWithTheEigenvalueCount(AuditReport.Scenario scenario) {
        MarkingDesign design = scenario.design();
        DesignMatrix matrix = new DesignMatrix(design);
        int n = matrix.columns();

        ExactRowSpace exact = new ExactRowSpace(n);
        double[][] information = new double[n][n];
        for (DesignCell cell : design.cells()) {
            int[] row = matrix.row(cell);
            exact.addRow(row);
            for (int i = 0; i < n; i++) {
                if (row[i] == 0) continue;
                for (int j = 0; j < n; j++) {
                    information[i][j] += (double) row[i] * row[j];
                }
            }
        }

        int numericNullity = SymmetricEigen.of(information).numericalNullity(1e-9);

        assertThat(numericNullity)
                .as("null dimension of '%s' by eigenvalues vs. by exact elimination", scenario.label())
                .isEqualTo(exact.nullity());
        assertThat(IdentifiabilityAudit.audit(design).certificate().rank()).isEqualTo(exact.rank());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyCertificateTheAuditIssuesSurvivesReVerification(AuditReport.Scenario scenario) {
        MarkingDesign design = scenario.design();
        AuditVerdict verdict = IdentifiabilityAudit.audit(design);

        assertThat(verdict.certificate().contradictionWith(design))
                .as("certificate for '%s'", scenario.label())
                .isEmpty();
        if (verdict.permitsEstimation()) {
            assertThatCode(() -> AnchoredDesign.of(design, verdict, Anchoring.standard()))
                    .doesNotThrowAnyException();
        }
    }
}
