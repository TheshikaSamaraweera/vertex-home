package com.democode.mlmsittu.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.notify.Notifications;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Notifications")
class NotificationsTest {

    @Autowired private Notifications notifications;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("a notification is raised, listed, and counted as unread")
    void raiseAndRead() {
        UUID user = newUser();

        notifications.raise(user, Notifications.REWARD_READY, "Pack ready", "Bedroom Pack", "/rewards");

        assertThat(notifications.unreadCount(user)).isEqualTo(1);

        var listed = notifications.list(user, 10);
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().title()).isEqualTo("Pack ready");
        assertThat(listed.getFirst().readAt()).as("unread until somebody opens it").isNull();

        notifications.markRead(user, listed.getFirst().id());
        assertThat(notifications.unreadCount(user)).isZero();
    }

    @Test
    @DisplayName("one person cannot mark another person's notification read")
    void markReadIsScopedToTheOwner() {
        UUID owner = newUser();
        UUID stranger = newUser();
        notifications.raise(owner, Notifications.REWARD_READY, "Theirs", null, null);

        UUID id = notifications.list(owner, 10).getFirst().id();
        notifications.markRead(stranger, id);

        // Still unread. Without the user_id in the WHERE clause a guessed identifier would clear
        // somebody else's bell — harmless in itself, and exactly the shape of hole that turns out
        // not to be harmless somewhere else.
        assertThat(notifications.unreadCount(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("raising one for nobody is ignored rather than thrown")
    void nullRecipientIsIgnored() {
        assertThatCode(() -> notifications.raise(null, "ANY", "No recipient", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a notification that cannot be written does not take the caller down with it")
    void failureToRaiseIsSwallowed() {
        // A user id that does not exist violates the foreign key. Every call site is in the middle
        // of approving a registration or issuing a pack, and a notification is the announcement
        // rather than the event — letting this propagate would roll back an approval because a
        // courtesy message could not be recorded.
        assertThatCode(
                        () ->
                                notifications.raise(
                                        UUID.randomUUID(), "ANY", "Orphan", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("mark all read clears the badge and leaves the history")
    void markAllRead() {
        UUID user = newUser();
        notifications.raise(user, "A", "One", null, null);
        notifications.raise(user, "B", "Two", null, null);
        notifications.raise(user, "C", "Three", null, null);

        notifications.markAllRead(user);

        assertThat(notifications.unreadCount(user)).isZero();
        assertThat(notifications.list(user, 10))
                .as("read, not deleted — the bell empties, the history does not")
                .hasSize(3);
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("notify-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Notification fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
