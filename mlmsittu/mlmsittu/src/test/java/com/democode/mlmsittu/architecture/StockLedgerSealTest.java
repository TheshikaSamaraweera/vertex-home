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
import java.util.Set;

/**
 * Seals the stock aggregate (development plan P2-04, architecture §4.1).
 *
 * <p>The rule the plan asks for is "never write {@code stock_level} outside
 * {@code StockLedgerService}". This enforces something slightly stronger and much easier to check
 * reliably: the entities and repositories behind stock are <b>reachable only from inside their own
 * package</b>. If no other class can name {@code StockLevelRepository}, no other class can write
 * through it, and there is no need to distinguish a read from a write.
 *
 * <p>{@code StockLedgerService} and {@code StockReconciliationService} live in that package and
 * are the way in. Both are free to be called from anywhere.
 *
 * <p><b>To see it work:</b> add {@code StockLevelRepository} as a constructor parameter to any
 * class outside {@code ..inventory.internal.stock..} — say {@code StockAdjustmentService} — and
 * run {@code ./gradlew test}. This test fails and the build stops. That was verified during
 * development; a rule never seen to fail is not known to work.
 *
 * <p>Phase 3 adds {@code ReservationService} to the same package. It takes the same row locks on
 * the same table, and belongs behind the same seal.
 */
@AnalyzeClasses(
        packages = StockLedgerSealTest.ROOT,
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class StockLedgerSealTest {

    static final String ROOT = "com.democode.mlmsittu";

    private static final String SEALED_PACKAGE = ROOT + ".inventory.internal.stock";

    /** Types that may not be named anywhere outside {@link #SEALED_PACKAGE}. */
    private static final Set<String> SEALED_TYPES =
            Set.of(
                    SEALED_PACKAGE + ".StockLevel",
                    SEALED_PACKAGE + ".StockLevelId",
                    SEALED_PACKAGE + ".StockLevelRepository",
                    SEALED_PACKAGE + ".StockMovement",
                    SEALED_PACKAGE + ".StockMovementRepository",
                    SEALED_PACKAGE + ".LedgerTotal");

    @ArchTest
    static final ArchRule stock_tables_are_reachable_only_through_the_ledger_service =
            ArchRuleDefinition.classes()
                    .should(
                            new ArchCondition<>(
                                    "not touch stock_level or stock_movement directly") {
                                @Override
                                public void check(JavaClass origin, ConditionEvents events) {
                                    if (origin.getPackageName().startsWith(SEALED_PACKAGE)) {
                                        return;
                                    }

                                    for (Dependency dependency :
                                            origin.getDirectDependenciesFromSelf()) {

                                        String target =
                                                dependency.getTargetClass().getFullName();
                                        if (!SEALED_TYPES.contains(target)) {
                                            continue;
                                        }

                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        origin,
                                                        "Stock is written only by"
                                                            + " StockLedgerService. Post a"
                                                            + " StockPosting instead of reaching"
                                                            + " for "
                                                                + dependency
                                                                        .getTargetClass()
                                                                        .getSimpleName()
                                                                + ": "
                                                                + dependency.getDescription()));
                                    }
                                }
                            });
}
