package com.democode.mlmsittu.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Module boundaries, enforced at build time (development plan P0-01).
 *
 * <p>The architecture asks for nine Gradle modules. This project keeps one module and expresses
 * the same boundaries as packages, because the protection that actually matters — a compile-time
 * failure when one module reaches into another's guts — comes from these rules, not from the build
 * layout. Splitting into real Gradle modules later is mechanical precisely because these rules
 * keep the dependency graph honest in the meantime.
 *
 * <p><b>To see it work</b> (this is the P0-01 verification):
 *
 * <ol>
 *   <li>Add a class under {@code com.democode.mlmsittu.catalogue.internal} that references
 *       {@code com.democode.mlmsittu.identity.internal.service.AuthService}.
 *   <li>Run {@code ./gradlew test} — {@code internals_are_private_to_their_module} fails and the
 *       build stops, naming both modules and the offending line.
 *   <li>Delete the class, re-run, confirm green.
 * </ol>
 *
 * <p>This was run during development and the rule fired as described, so the rule is known to be
 * capable of failing — a boundary test that has never been seen to fail proves nothing.
 */
@AnalyzeClasses(
        packages = ModuleBoundaryTest.ROOT,
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ModuleBoundaryTest {

    static final String ROOT = "com.democode.mlmsittu";

    /** The shared kernel. Every module may depend on it; it may depend on none of them. */
    private static final String SHARED = "shared";

    /**
     * The rule that makes the layout real: a module's {@code internal} packages belong to that
     * module alone. Cross-module traffic goes through {@code api} packages, so each module's
     * public surface is a deliberate, reviewable decision rather than whatever happened to be
     * reachable.
     */
    @ArchTest
    static final ArchRule internals_are_private_to_their_module =
            ArchRuleDefinition.classes()
                    .should(
                            new ArchCondition<>(
                                    "not reach into another module's internal packages") {
                                @Override
                                public void check(JavaClass origin, ConditionEvents events) {
                                    String originModule = moduleOf(origin.getPackageName());
                                    if (originModule == null) {
                                        return;
                                    }

                                    for (Dependency dependency :
                                            origin.getDirectDependenciesFromSelf()) {

                                        String targetPackage =
                                                dependency.getTargetClass().getPackageName();
                                        String targetModule = moduleOf(targetPackage);

                                        if (targetModule == null
                                                || targetModule.equals(originModule)
                                                || !isInternal(targetPackage)) {
                                            continue;
                                        }

                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        origin,
                                                        "Module '"
                                                                + originModule
                                                                + "' reaches into internals of '"
                                                                + targetModule
                                                                + "': "
                                                                + dependency.getDescription()));
                                    }
                                }
                            });

    /**
     * Dependencies point inward. {@code shared} carries audit, errors, the response envelope and
     * rate limiting — things every module needs. The moment it depends on a feature module, it
     * stops being a kernel and becomes a cycle.
     */
    @ArchTest
    static final ArchRule shared_kernel_depends_on_no_feature_module =
            ArchRuleDefinition.classes()
                    .should(
                            new ArchCondition<>("keep the shared kernel free of feature modules") {
                                @Override
                                public void check(JavaClass origin, ConditionEvents events) {
                                    if (!SHARED.equals(moduleOf(origin.getPackageName()))) {
                                        return;
                                    }

                                    for (Dependency dependency :
                                            origin.getDirectDependenciesFromSelf()) {

                                        String targetModule =
                                                moduleOf(
                                                        dependency
                                                                .getTargetClass()
                                                                .getPackageName());

                                        if (targetModule == null || SHARED.equals(targetModule)) {
                                            continue;
                                        }

                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        origin,
                                                        "shared kernel depends on module '"
                                                                + targetModule
                                                                + "': "
                                                                + dependency.getDescription()));
                                    }
                                }
                            });

    // ------------------------------------------------------------------ helpers

    /**
     * @return the module name for a package, or null for anything outside the application (JDK,
     *     Spring, Jackson) and for the root package itself.
     */
    private static String moduleOf(String packageName) {
        if (packageName == null || !packageName.startsWith(ROOT + ".")) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length() + 1);
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }

    private static boolean isInternal(String packageName) {
        return packageName.contains(".internal.") || packageName.endsWith(".internal");
    }
}
