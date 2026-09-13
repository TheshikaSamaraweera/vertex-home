package com.democode.mlmsittu.shared.notify;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live delivery of notifications to open browser tabs, over server-sent events.
 *
 * <h2>Why SSE and not WebSocket</h2>
 *
 * <p>Everything here travels one way. The browser never sends a notification, it only receives
 * them, and SSE is the protocol shaped like that: a plain HTTP response that stays open, with
 * automatic reconnection built into the browser's own {@code EventSource}. A WebSocket would add a
 * second protocol, an upgrade handshake through nginx, and a reconnection loop to write by hand,
 * in exchange for a direction that is never used.
 *
 * <h2>What happens when the connection drops</h2>
 *
 * <p>It reconnects on its own, and nothing is lost. Every notification is written to the database
 * before it is pushed, so the list and the unread count are correct whether or not the push
 * arrived. This stream is an optimisation over refetching, not the source of truth — which is why
 * a missed event is invisible rather than a bug.
 *
 * <h2>One instance</h2>
 *
 * <p>Emitters live in this process's memory, so with two application containers a push only
 * reaches the tabs connected to the same one. That is correct for this deployment — a single
 * container on one EC2 instance — and would need Postgres {@code LISTEN/NOTIFY} to fan out if that
 * ever changes. The failure mode if somebody scales it up without doing that is mild and specific:
 * some tabs stop seeing toasts and keep working normally, because the database still has every
 * notification.
 */
@Component
public class NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(NotificationStream.class);

    /**
     * Half an hour, then the browser reconnects.
     *
     * <p>Not infinite. A connection held open forever accumulates emitters for tabs that were
     * closed without the server noticing — a laptop lid, a dropped network — and each one holds a
     * request object. Expiring them costs a reconnect nobody sees and bounds the leak.
     */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** One person may have several tabs open, and each expects its own toast. */
    private final Map<UUID, List<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        byUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));

        try {
            // An immediate event, so the browser treats the connection as established rather than
            // sitting on a response with no body. It also proves the path works end to end, which
            // through a proxy that might be buffering is worth knowing at once.
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException gone) {
            remove(userId, emitter);
        }
        return emitter;
    }

    void push(UUID userId, Notifications.Notification notification) {
        List<SseEmitter> emitters = byUser.get(userId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("notification")
                                .data(
                                        Map.of(
                                                "id", notification.id().toString(),
                                                "kind", notification.kind(),
                                                "title", notification.title(),
                                                "body",
                                                        notification.body() == null
                                                                ? ""
                                                                : notification.body(),
                                                "link",
                                                        notification.link() == null
                                                                ? ""
                                                                : notification.link())));
            } catch (IOException | IllegalStateException gone) {
                // The tab went away between the check and the send. Ordinary, not an error.
                remove(userId, emitter);
            }
        }
    }

    /**
     * A comment down every open connection, every 25 seconds.
     *
     * <p>Idle connections are closed by proxies and by load balancers — nginx's default read
     * timeout is 60 seconds — and a dead SSE connection looks exactly like a quiet one from the
     * browser's side until it tries to use it. A colon-prefixed line is a comment in the SSE
     * format: the browser ignores it entirely, and every hop in between sees traffic.
     */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        byUser.forEach(
                (userId, emitters) -> {
                    for (SseEmitter emitter : emitters) {
                        try {
                            emitter.send(SseEmitter.event().comment("keep-alive"));
                        } catch (IOException | IllegalStateException gone) {
                            remove(userId, emitter);
                        }
                    }
                });
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> emitters = byUser.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        // Removing the empty list keeps the map from growing by one entry per user who ever
        // signed in, which over months is the whole user table.
        if (emitters.isEmpty()) {
            byUser.remove(userId, emitters);
        }
    }

    /** For the health of the thing itself: how many connections are open right now. */
    public int openConnections() {
        return byUser.values().stream().mapToInt(List::size).sum();
    }
}
