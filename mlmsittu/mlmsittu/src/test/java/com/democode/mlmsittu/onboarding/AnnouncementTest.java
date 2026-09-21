package com.democode.mlmsittu.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.onboarding.internal.announce.AnnouncementService;
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

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("announcer-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Announcement fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
