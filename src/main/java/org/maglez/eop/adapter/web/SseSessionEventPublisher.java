package org.maglez.eop.adapter.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.usecase.SessionEvent;
import org.maglez.eop.usecase.SessionEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans session change notifications out to whoever is listening.
 *
 * <p>Server-sent events over the emitter that ships with Spring MVC, so no new
 * dependency and no second protocol (ADR-014).
 *
 * <p><strong>The registry is a broadcast list, not a presence list.</strong> An
 * entry means "the last write to this connection succeeded", which is a weaker claim
 * than "somebody is there": a client that closed its laptop lid is only discovered on
 * the next write. The EOP-8 spike watched this happen — two subscribers reported
 * live after both browsers had gone. Anything that needs to know who is actually
 * present must ask the database, and {@code connectionStatus} is documented as
 * advisory for the same reason.
 *
 * <p><strong>No event history is kept.</strong> There is nothing to replay and
 * {@code Last-Event-ID} is not honoured, because a restart resets any counter and an
 * identifier minted by a previous process names an event this one cannot produce.
 * Reconnection is a re-read of {@code GET /api/v1/sessions/{sessionId}}, never a
 * replay. Keeping a replayable log would mean a second source of truth, and the
 * database is the only authority.
 *
 * <p><strong>Publishing never fails the request that triggered it.</strong> A
 * departed subscriber is the normal case, not an error: a player whose join
 * succeeded must not receive a failure because somebody else closed a tab.
 */
@Component
public class SseSessionEventPublisher implements SessionEventPublisher, DisposableBean {

    /**
     * How long a client should wait before reconnecting, in milliseconds.
     *
     * <p>Written once per subscription as the stream's {@code retry:} field. Three
     * seconds is long enough that a restarting container is not hammered and short
     * enough that a facilitator does not narrate the wait.
     */
    private static final long RECONNECT_HINT_MILLIS = 3000L;

    private static final Logger LOG = LoggerFactory.getLogger(SseSessionEventPublisher.class);

    private final Map<UUID, Collection<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeats;

    private final Duration heartbeatInterval;

    SseSessionEventPublisher(final RealtimeProperties properties) {
        Objects.requireNonNull(properties, "properties is required");
        this.heartbeatInterval = properties.heartbeatInterval();
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        final long period = this.heartbeatInterval.toMillis();
        this.heartbeats.scheduleAtFixedRate(this::beat, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a new listener for one session and returns its open stream.
     *
     * <p>The emitter is created with a timeout of zero, meaning the servlet container
     * will not time the connection out on its own. That is safe only because of the
     * heartbeat: detection of a dead peer is this class's job, not the container's,
     * and a container timeout would otherwise close healthy idle lobbies.
     *
     * @param sessionId the session whose changes should be delivered
     * @return the stream to return from a controller method
     */
    public SseEmitter subscribe(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        final SseEmitter emitter = new SseEmitter(0L);
        final Collection<SseEmitter> forSession =
                subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
        forSession.add(emitter);

        emitter.onCompletion(() -> forget(sessionId, emitter));
        emitter.onTimeout(() -> forget(sessionId, emitter));
        emitter.onError(failure -> forget(sessionId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .reconnectTime(RECONNECT_HINT_MILLIS)
                    .comment("subscribed"));
        }
        catch (final IOException | IllegalStateException gone) {
            forget(sessionId, emitter);
        }
        return emitter;
    }

    @Override
    public void publish(final SessionEvent event) {
        Objects.requireNonNull(event, "event is required");

        final Collection<SseEmitter> forSession = subscribers.get(event.sessionId());
        if (forSession == null || forSession.isEmpty()) {
            return;
        }

        final SessionEventDto payload = SessionEventDto.from(event);
        for (final SseEmitter emitter : forSession) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().wireName())
                        .data(payload));
            }
            catch (final IOException | IllegalStateException gone) {
                forget(event.sessionId(), emitter);
            }
        }
    }

    /**
     * Reports how many streams are currently registered for a session.
     *
     * <p>For tests only, and deliberately not exposed over HTTP: this number
     * over-reports, so publishing it would invite somebody to treat it as a count of
     * people in the lobby.
     *
     * @param sessionId the session to count
     * @return the number of registered emitters, which is an upper bound on listeners
     */
    int subscriberCount(final UUID sessionId) {
        final Collection<SseEmitter> forSession = subscribers.get(sessionId);
        return forSession == null ? 0 : forSession.size();
    }

    /**
     * Drops every registration without touching the database.
     *
     * <p>For tests: this is the closest in-process equivalent of the container being
     * replaced by a deployment. What survives it is what a reconnecting player will
     * actually see.
     */
    void forgetEveryone() {
        subscribers.clear();
    }

    @Override
    public void destroy() {
        heartbeats.shutdownNow();
        subscribers.values().forEach(forSession -> forSession.forEach(this::completeQuietly));
        subscribers.clear();
    }

    /**
     * Writes a comment frame to every open stream.
     *
     * <p>The frame carries no data and no event name, so a client ignores it. Its
     * only purpose is to be a write: a write is the only thing that discovers a
     * connection the peer has already abandoned, and it also keeps intermediaries
     * from closing a quiet lobby as idle.
     *
     * <p>The whole body is guarded, because an exception escaping a scheduled task
     * cancels the schedule silently — the failure mode would be heartbeats simply
     * stopping, which is precisely the condition they exist to detect.
     */
    private void beat() {
        try {
            subscribers.forEach((sessionId, forSession) -> {
                for (final SseEmitter emitter : forSession) {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                    catch (final IOException | IllegalStateException gone) {
                        forget(sessionId, emitter);
                    }
                }
            });
        }
        catch (final RuntimeException unexpected) {
            LOG.warn("Heartbeat sweep failed; streams remain open and the next sweep will retry", unexpected);
        }
    }

    private void forget(final UUID sessionId, final SseEmitter emitter) {
        final Collection<SseEmitter> forSession = subscribers.get(sessionId);
        if (forSession == null) {
            return;
        }
        forSession.remove(emitter);
        if (forSession.isEmpty()) {
            subscribers.remove(sessionId, forSession);
        }
    }

    private void completeQuietly(final SseEmitter emitter) {
        try {
            emitter.complete();
        }
        catch (final RuntimeException alreadyGone) {
            LOG.debug("Stream was already closed when shutting down", alreadyGone);
        }
    }
}
