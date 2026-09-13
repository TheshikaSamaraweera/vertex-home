package com.democode.mlmsittu.shared.notify;

import com.democode.mlmsittu.shared.audit.api.AuditActor;
import com.democode.mlmsittu.shared.error.UnauthenticatedException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The bell, for everybody.
 *
 * <p>No role guard beyond "signed in", deliberately: staff and customers both have notifications,
 * and every query here is scoped to the caller's own id rather than taking one as a parameter.
 * There is no notification anybody can read but their own, so there is nothing for a role to
 * protect.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final Notifications notifications;
    private final NotificationStream stream;

    public NotificationController(Notifications notifications, NotificationStream stream) {
        this.notifications = notifications;
        this.stream = stream;
    }

    /**
     * The signed-in user's id, read through the {@link AuditActor} port.
     *
     * <p>Not {@code identity.api.CurrentUser}, which would be the obvious choice and is the wrong
     * one here: the shared kernel may not depend on a feature module, and ArchUnit enforces it.
     * {@code AuditActor} exists for exactly this — a contract declared in {@code shared} that
     * whatever {@code identity} puts in the security context implements.
     */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuditActor actor)) {
            throw new UnauthenticatedException("UNAUTHENTICATED", "Authentication is required.");
        }
        return actor.auditActorId();
    }

    public record NotificationListResponse(
            List<Notifications.Notification> data, int unreadCount) {}

    /** The bell's contents and its badge in one request, because the UI always wants both. */
    @GetMapping
    public NotificationListResponse list(@RequestParam(defaultValue = "50") int limit) {
        UUID userId = currentUserId();
        return new NotificationListResponse(
                notifications.list(userId, limit), notifications.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    public NotificationListResponse markRead(@PathVariable UUID id) {
        UUID userId = currentUserId();
        notifications.markRead(userId, id);
        return new NotificationListResponse(
                notifications.list(userId, 50), notifications.unreadCount(userId));
    }

    @PostMapping("/read-all")
    public NotificationListResponse markAllRead() {
        UUID userId = currentUserId();
        notifications.markAllRead(userId);
        return new NotificationListResponse(
                notifications.list(userId, 50), notifications.unreadCount(userId));
    }

    /**
     * The live connection.
     *
     * <p>Returns a response that never finishes, which is the point. Spring hands the request to a
     * worker only when there is something to write, so an idle connection holds no thread — which
     * is what makes one of these per open tab affordable.
     *
     * <p>Behind nginx this needs {@code proxy_buffering off} on the location, or the proxy holds
     * each event in its buffer waiting for more and the whole thing arrives at once, minutes late.
     * It looks exactly like the feature not working.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return stream.open(currentUserId());
    }
}
