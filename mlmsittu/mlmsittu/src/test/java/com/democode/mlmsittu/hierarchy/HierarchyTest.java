package com.democode.mlmsittu.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.shared.businessid.PositionalId;
import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.hierarchy.internal.HierarchyRepository;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.config.SystemConfigService;
import com.democode.mlmsittu.shared.error.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** P4-02, P4-03 and P4-04. */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Referral hierarchy")
class HierarchyTest {

    @Autowired private DistributorService distributors;
    @Autowired private HierarchyRepository hierarchy;
    @Autowired private SystemConfigService config;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Restores the <b>shipped</b> cap, not a literal.
     *
     * <p>This used to set "4" and leak it: {@code system_config} is a table, every test class
     * shares the database, and a class that pinned the cap low left it low for whatever ran next.
     * That was harmless while the shipped value was also four — and would have started failing
     * the reward tests the moment the programme went to five stages, in a way that pointed at
     * entirely the wrong file.
     */
    @BeforeEach
    void resetCap() {
        config.set(
                SystemConfigService.REFERRAL_MAX_DIRECT,
                String.valueOf(StageProgress.TOTAL_STAGES));
    }

    // ==============================================================================
    // P4-02 / P4-03 · schema and queries
    // ==============================================================================

    @Test
    @DisplayName("P4-02 · path is ltree with both GIST and BTREE indexes")
    void schemaIsAsSpecified() {
        String columnType =
                jdbc.queryForObject(
                        """
                        SELECT udt_name FROM information_schema.columns
                        WHERE table_name = 'distributor' AND column_name = 'path'
                        """,
                        String.class);
        assertThat(columnType).isEqualTo("ltree");

        List<String> indexDefinitions =
                jdbc.queryForList(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'distributor'",
                        String.class);

        assertThat(indexDefinitions).anyMatch(definition -> definition.contains("gist"));
        assertThat(indexDefinitions)
                .anyMatch(definition -> definition.contains("btree") && definition.contains("path"));
    }

    @Test
    @DisplayName("P4-03 · descendants stop at the requested depth, ancestors come root-first")
    void treeQueriesRespectDepthAndOrder() {
        UUID root = activate(newDistributor(null), null);
        UUID level1 = activate(newDistributor(root), root);
        UUID level2 = activate(newDistributor(level1), level1);
        UUID level3 = activate(newDistributor(level2), level2);

        List<DistributorNode> twoLevels = distributors.downline(root, 2);
        List<UUID> ids = twoLevels.stream().map(DistributorNode::id).toList();

        assertThat(ids).contains(level1, level2);
        assertThat(ids).as("level 3 is beyond the requested depth").doesNotContain(level3);

        List<DistributorNode> upline = distributors.upline(level3);
        assertThat(upline.stream().map(DistributorNode::id).toList())
                .as("root first")
                .containsExactly(root, level1, level2);
    }

    @Test
    @DisplayName("P4-03 · the descendant query uses the GIST index, not a sequential scan")
    void descendantQueryUsesTheIndex() {
        UUID root = activate(newDistributor(null), null);
        for (int i = 0; i < 3; i++) {
            activate(newDistributor(root), root);
        }
        String rootPath = hierarchy.pathOf(root);

        // Planner cost estimates favour a sequential scan on a tiny table regardless of indexes,
        // so ask it to disregard that option and confirm the index is genuinely usable for this
        // operator. Without the GIST index this plan would not exist at all.
        jdbc.execute("SET enable_seqscan = off");
        try {
            List<String> plan =
                    jdbc.queryForList(
                            "EXPLAIN SELECT id FROM distributor WHERE path <@ CAST(? AS ltree)",
                            String.class,
                            rootPath);

            String planText = String.join(" ", plan);
            assertThat(planText)
                    .as("plan was: %s", planText)
                    .containsIgnoringCase("idx_dist_path_gist");
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }

    // ==============================================================================
    // P4-04 · width cap
    // ==============================================================================

    @Test
    @DisplayName("P4-04 · the cap admits exactly as many children as it allows, and no more")
    void capIsEnforced() {
        int cap = StageProgress.TOTAL_STAGES;
        UUID parent = activate(newDistributor(null), null);

        for (int i = 0; i < cap; i++) {
            activate(newDistributor(parent), parent);
        }

        UUID oneTooMany = newDistributor(parent);
        try {
            distributors.attachToReferrer(oneTooMany, parent);
            throw new AssertionError("child " + (cap + 1) + " should have been refused");
        } catch (ConflictException expected) {
            assertThat(expected.getCode()).isEqualTo("REFERRER_AT_CAPACITY");
            assertThat(expected.getProperties()).containsEntry("maxDirect", cap);
        }
    }

    @Test
    @DisplayName("P4-04 · the cap can be lowered at runtime, but never raised past the seats")
    void capIsRuntimeConfigurableDownwardOnly() {
        int seats = PositionalId.SEATS;
        UUID parent = activate(newDistributor(null), null);
        for (int i = 0; i < seats; i++) {
            activate(newDistributor(parent), parent);
        }

        // P4-04 asked for a cap raisable at runtime with no restart, and until positional
        // identifiers arrived it was. It cannot be any more, and the reason is structural rather
        // than an oversight: a seat is a single digit 1-5 appended to the parent's ID, and 6-9 are
        // reserved for root identifiers. There is no sixth seat to allocate, so raising the
        // configured cap changes what the counter permits and nothing else.
        config.set(SystemConfigService.REFERRAL_MAX_DIRECT, String.valueOf(seats + 2));

        UUID extra = newDistributor(parent);
        assertThatThrownBy(() -> distributors.attachToReferrer(extra, parent))
                .as("the configured cap cannot invent a sixth seat")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("referral seats");

        config.set(SystemConfigService.REFERRAL_MAX_DIRECT, String.valueOf(seats));

        // activeChildrenOf, not children: the refused attach above leaves a pending row behind,
        // and a distributor who was never approved is not one of this parent's referrals.
        assertThat(activeChildrenOf(parent)).hasSize(seats);

        // Lowering it still works, and still only affects the next approval.
        config.set(SystemConfigService.REFERRAL_MAX_DIRECT, "2");
        assertThat(activeChildrenOf(parent))
                .as("existing records are left alone")
                .hasSize(seats);
        config.set(SystemConfigService.REFERRAL_MAX_DIRECT, String.valueOf(seats));
    }

    @Test
    @DisplayName("P4-04 · concurrent approvals cannot breach the cap")
    void capHoldsUnderConcurrency() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID parent = activate(newDistributor(null), null);

            // Twice as many applicants as slots, so the race is real. Counting without the
            // parent row lock lets several read the same value and all conclude there is room.
            int contenders = StageProgress.TOTAL_STAGES * 2;
            List<UUID> pending = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                pending.add(newDistributor(parent));
            }

            CountDownLatch startGun = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();

            List<Callable<Void>> tasks = new ArrayList<>();
            for (UUID candidate : pending) {
                tasks.add(
                        () -> {
                            startGun.await();
                            try {
                                distributors.attachToReferrer(candidate, parent);
                                accepted.incrementAndGet();
                            } catch (ConflictException expected) {
                                refused.incrementAndGet();
                            } catch (Exception e) {
                                unexpected.incrementAndGet();
                            }
                            return null;
                        });
            }

            try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
                tasks.forEach(pool::submit);
                startGun.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            }

            int cap = StageProgress.TOTAL_STAGES;
            assertThat(accepted.get()).as("accepted in round %d", round).isEqualTo(cap);
            assertThat(refused.get()).as("refused in round %d", round).isEqualTo(contenders - cap);
            assertThat(unexpected.get()).as("unexpected errors in round %d", round).isZero();

            // Capacity is consumed at approval, not at application: the refused applicants still
            // exist as pending rows pointing at this referrer, and should. What must never exceed
            // the cap is the number actually placed in the tree.
            assertThat(activeChildrenOf(parent))
                    .as("the tree itself must not exceed the cap")
                    .hasSize(cap);
        }
    }

    private List<DistributorNode> activeChildrenOf(UUID parent) {
        return distributors.children(parent).stream()
                .filter(child -> "active".equals(child.status()))
                .toList();
    }

    @Test
    @DisplayName("a deleted child's seat is retired with it, and cannot be refilled")
    void deletionRetiresTheSeatForGood() {
        UUID parent = activate(newDistributor(null), null);
        List<String> issued = new ArrayList<>();
        UUID firstChild = null;

        for (int i = 0; i < StageProgress.TOTAL_STAGES; i++) {
            UUID child = newDistributor(parent);
            issued.add(distributors.attachToReferrer(child, parent));
            if (i == 0) {
                firstChild = child;
            }
        }

        distributors.softDelete(firstChild);
        assertThat(activeChildrenOf(parent)).hasSize(StageProgress.TOTAL_STAGES - 1);

        // Positional identifiers changed this. The seat IS the identifier — the first child is 11,
        // the second 12 — so refilling seat 1 would mean reissuing 11 to a second person, after a
        // card had been printed with it and an audit trail written against it.
        //
        // The count says there is room and the seats say there is not. That disagreement is the
        // honest answer, and the error names it rather than reporting a capacity problem.
        UUID replacement = newDistributor(parent);
        assertThatThrownBy(() -> distributors.attachToReferrer(replacement, parent))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be refilled");

        assertThat(issued).as("every seat was issued exactly once").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the forest returns every root's subtree in one call, still capped by depth")
    void forestWalksEveryRoot() {
        UUID rootA = activate(newDistributor(null), null);
        UUID a1 = activate(newDistributor(rootA), rootA);
        UUID a2 = activate(newDistributor(a1), a1);
        UUID a3 = activate(newDistributor(a2), a2);

        UUID rootB = activate(newDistributor(null), null);
        UUID b1 = activate(newDistributor(rootB), rootB);

        List<UUID> shallow = distributors.forest(2).stream().map(DistributorNode::id).toList();

        assertThat(shallow)
                .as("both roots and their first two levels, from one call")
                .contains(rootA, a1, a2, rootB, b1);
        assertThat(shallow).as("the depth cap still applies per root").doesNotContain(a3);

        assertThat(distributors.forest(3).stream().map(DistributorNode::id).toList())
                .contains(a3);
    }

    @Test
    @DisplayName("a node carries its own stage progress, so drawing a tree needs no extra queries")
    void nodesCarryTheirStage() {
        UUID root = activate(newDistributor(null), null);
        activate(newDistributor(root), root);
        activate(newDistributor(root), root);

        DistributorNode node =
                distributors.roots().stream()
                        .filter(candidate -> candidate.id().equals(root))
                        .findFirst()
                        .orElseThrow();

        // Two referrals placed, so two of the four §0.2 stages are complete — and the number
        // arrives on the node itself rather than needing a request per distributor.
        assertThat(node.stagesCompleted()).isEqualTo(2);
        assertThat(node.bonusEligible()).isFalse();
        assertThat(node.directChildCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newDistributor(UUID referrer) {
        AppUser user = new AppUser();
        user.setEmail("hier-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Hierarchy fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        UUID userId = users.saveAndFlush(user).getId();
        return distributors.createPending(userId, referrer);
    }

    private UUID activate(UUID distributorId, UUID referrerId) {
        distributors.attachToReferrer(distributorId, referrerId);
        return distributorId;
    }
}
