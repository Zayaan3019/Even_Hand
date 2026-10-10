package in.ac.iitm.evenhand.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Rules about the whole system that a reviewer should not have to remember. */
class ArchitectureTest {

    private static final JavaClasses ALL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("in.ac.iitm.evenhand");

    @Test
    @DisplayName("nothing leaves the machine: only the loopback page server touches java.net, and no class is a client")
    void noNetwork() {
        noClasses().that().doNotHaveFullyQualifiedName(LocalServer.class.getName())
                .and().doNotHaveFullyQualifiedName(Main.class.getName())
                .should().dependOnClassesThat().resideInAnyPackage("java.net..", "javax.net..", "com.sun.net..")
                .check(ALL);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.Socket")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URL")
                .check(ALL);
    }

    @Test
    @DisplayName("dependencies flow downward: the estimator knows nothing about grades or reports")
    void estimatorIsUpstreamOfConsequence() {
        noClasses().that().resideInAPackage("..estimate..")
                .should().dependOnClassesThat().resideInAnyPackage("..outcome..", "..report..", "..app..")
                .check(ALL);
    }

    @Test
    @DisplayName("the audit is upstream of everything: it depends on no later stage")
    void auditIsUpstreamOfEverything() {
        noClasses().that().resideInAPackage("..design..")
                .should().dependOnClassesThat().resideInAnyPackage("..estimate..", "..outcome..", "..report..", "..app..")
                .check(ALL);
    }
}
