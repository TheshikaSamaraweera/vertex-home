package com.democode.mlmsittu.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.onboarding.internal.announce.AnnouncementService;
import com.democode.mlmsittu.onboarding.internal.announce.AnnouncementService.Fields;
import com.democode.mlmsittu.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Announcements")
class AnnouncementTest {

    private static final String GOOD_BODY =
            """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Notice"}]},
              {"type":"paragraph","content":[
                {"type":"text","marks":[{"type":"bold"}],"text":"Bold"},
                {"type":"text","marks":[{"type":"highlight"}],"text":" and highlighted"}]}]}
            """;

    @Autowired private AnnouncementService announcements;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DistributorService distributors;

    @Test
    @DisplayName("a draft reaches nobody until it is published")
    void draftsAreInvisible() {
        UUID author = newUser();
        UUID id = announcements.create("Draft notice", null, GOOD_BODY, null, null, author);

        assertThat(announcements.listLive())
                .as("an administrator writing over two sittings is not broadcasting in between")
                .noneMatch(a -> a.id().equals(id));

        announcements.publish(id);

        assertThat(announcements.listLive()).anyMatch(a -> a.id().equals(id));
    }

    @Test
    @DisplayName("publishing twice is refused, so one notice is one notification")
    void publishingIsOnce() {
        UUID id = announcements.create("Once only", null, GOOD_BODY, null, null, newUser());
        announcements.publish(id);

        assertThatThrownBy(() -> announcements.publish(id))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("ALREADY_PUBLISHED"));
    }

    @Test
    @DisplayName("an expired announcement drops off without being deleted")
    void expiryHidesButKeeps() {
        UUID id =
                announcements.create(
                        "Time limited",
                        null,
                        GOOD_BODY,
                        null,
                        Instant.now().plusSeconds(3600),
                        newUser());
        announcements.publish(id);
        assertThat(announcements.listLive()).anyMatch(a -> a.id().equals(id));

        jdbc.update("UPDATE announcement SET expires_at = now() - INTERVAL '1 hour' WHERE id = ?", id);

        assertThat(announcements.listLive()).noneMatch(a -> a.id().equals(id));
        // Still there. What was said to customers is a record worth keeping.
        assertThat(announcements.get(id).title()).isEqualTo("Time limited");
    }

    @Test
    @DisplayName("a block type the renderer does not know is refused on the way in")
    void unknownBlocksAreRefused() {
        // The renderer skips these anyway, so this is the second of two defences — but it is the
        // one that keeps the stored data honest, so a future renderer that trusts its input does
        // not inherit a hole that was already in the database.
        String withScript =
                "{\"type\":\"doc\",\"content\":[{\"type\":\"script\",\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"alert(1)\"}]}]}";

        assertThatThrownBy(
                        () -> announcements.create("Attack", null, withScript, null, null, newUser()))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("UNSUPPORTED_CONTENT"));
    }

    @Test
    @DisplayName("a link mark is refused — a notice carries no URL anybody else will follow")
    void linksAreRefused() {
        String withLink =
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                        + "[{\"type\":\"text\",\"marks\":[{\"type\":\"link\","
                        + "\"attrs\":{\"href\":\"http://evil.example\"}}],\"text\":\"click\"}]}]}";

        assertThatThrownBy(
                        () -> announcements.create("Phish", null, withLink, null, null, newUser()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an empty body is refused")
    void emptyBodiesAreRefused() {
        assertThatThrownBy(() -> announcements.create("Nothing", null, "", null, null, newUser()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("editing a draft leaves it a draft")
    void editingDoesNotPublish() {
        UUID id = announcements.create("Still drafting", null, GOOD_BODY, null, null, newUser());

        announcements.update(id, "Still drafting, v2", null, GOOD_BODY, null, null);

        assertThat(announcements.get(id).publishedAt()).isNull();
        assertThat(announcements.listLive()).noneMatch(a -> a.id().equals(id));
    }

    @Test
    @DisplayName("editing a taken-down announcement cannot put it back up")
    void editingDoesNotRevive() {
        UUID id = announcements.create("Was up", null, GOOD_BODY, null, null, newUser());
        announcements.publish(id);
        announcements.withdraw(id);
        assertThat(announcements.listLive()).noneMatch(a -> a.id().equals(id));

        // What the editor sends when somebody clears the "take down on" date, or moves it on.
        announcements.update(id, "Was up, reworded", null, GOOD_BODY, null, null);
        announcements.update(
                id, "Was up, again", null, GOOD_BODY, null, Instant.now().plusSeconds(86_400));

        assertThat(announcements.listLive()).noneMatch(a -> a.id().equals(id));
        assertThat(announcements.get(id).title()).isEqualTo("Was up, again");
    }

    // ==============================================================================
    // Marketing
    // ==============================================================================

    @Test
    @DisplayName("a members-only post is hidden from somebody not yet approved; an everyone post is not")
    void audienceDecidesWhoSees() {
        UUID author = newUser();
        UUID forMembers = publish(marketing("Members only", "members", null, null), author);
        UUID forEveryone = publish(marketing("For everyone", "everyone", null, null), author);
        UUID applicant = newUser();
        UUID member = newMember();

        assertThat(announcements.listLiveFor(applicant))
                .extracting(AnnouncementService.Announcement::id)
                .contains(forEveryone)
                .doesNotContain(forMembers);
        assertThat(announcements.listLiveFor(member))
                .extracting(AnnouncementService.Announcement::id)
                .contains(forEveryone, forMembers);
    }

    @Test
    @DisplayName("a button opens a portal page by name — never a URL, and never half set")
    void buttonsOnlyOpenPortalPages() {
        UUID author = newUser();

        UUID ok = announcements.create(marketing("Shop packs", "everyone", "See packs", "item_packs"), author);
        assertThat(announcements.get(ok).ctaTarget()).isEqualTo("item_packs");

        assertThatThrownBy(
                        () ->
                                announcements.create(
                                        marketing("Phish", "everyone", "Claim", "https://evil.example"),
                                        author))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getCode()).isEqualTo("INVALID_CTA_TARGET"));
        assertThatThrownBy(
                        () -> announcements.create(marketing("Half", "everyone", "Click", null), author))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getCode()).isEqualTo("CTA_INCOMPLETE"));
    }

    @Test
    @DisplayName("views and clicks count people, not page loads")
    void engagementCountsPeopleOnce() {
        UUID id = publish(marketing("Counted", "everyone", "See packs", "item_packs"), newUser());
        UUID first = newUser();
        UUID second = newUser();

        announcements.recordEngagement(id, first, "view");
        announcements.recordEngagement(id, first, "view");
        announcements.recordEngagement(id, second, "view");
        announcements.recordEngagement(id, first, "click");

        var counted = announcements.get(id);
        assertThat(counted.views()).isEqualTo(2);
        assertThat(counted.clicks()).isEqualTo(1);
        assertThat(announcements.listLiveFor(first))
                .filteredOn(a -> a.id().equals(id))
                .singleElement()
                .satisfies(a -> assertThat(a.seen()).isTrue());
    }

    @Test
    @DisplayName("nobody can register engagement with a post they cannot see")
    void engagementNeedsAVisiblePost() {
        UUID id = publish(marketing("Members only", "members", null, null), newUser());

        assertThatThrownBy(() -> announcements.recordEngagement(id, newUser(), "view"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an edit through the old call keeps the post's banner, audience and button")
    void theOldEditKeepsMarketingSettings() {
        UUID id =
                announcements.create(
                        new Fields("Sale", null, GOOD_BODY, null, null, "offer", true, "everyone", "See packs", "item_packs"),
                        newUser());

        announcements.update(id, "Sale, reworded", null, GOOD_BODY, null, null);

        var after = announcements.get(id);
        assertThat(after.featured()).isTrue();
        assertThat(after.category()).isEqualTo("offer");
        assertThat(after.audience()).isEqualTo("everyone");
        assertThat(after.ctaTarget()).isEqualTo("item_packs");
    }

    private static Fields marketing(String title, String audience, String ctaLabel, String ctaTarget) {
        return new Fields(title, null, GOOD_BODY, null, null, "offer", true, audience, ctaLabel, ctaTarget);
    }

    private UUID publish(Fields fields, UUID author) {
        UUID id = announcements.create(fields, author);
        announcements.publish(id);
        return id;
    }

    private UUID newMember() {
        UUID userId = newUser();
        UUID distributorId = distributors.createPending(userId, null);
        distributors.attachToReferrer(distributorId, null);
        return userId;
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("announcer-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Announcement fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
