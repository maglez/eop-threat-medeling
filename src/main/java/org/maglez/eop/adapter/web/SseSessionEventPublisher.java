package org.maglez.eop.adapter.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.usecase.SessionEvent;
import org.maglez.eop.usecase.SessionEventPublisher;
import org.maglez.eop.usecase.TooManySubscribersException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><strong>Subscriber caps prevent unbounded resource consumption (EOP-20, ADR-034).</strong>
 * A per-session cap of {@value #MAX_SUBSCRIBERS_PER_SESSION} (2× MAXIMUM_PLAYERS to allow
 * reconnect churn) and a global cap of {@value #MAX_TOTAL_SUBSCRIBERS} are enforced before
 * any emitter is created. Both caps throw {@link TooManySubscribersException}, mapped to
 * HTTP 429 by {@code GlobalExceptionHandler}.
 */
@Component
public class SseSessionEventPublisher implements SessionEventPublisher, DisposableBean {

    /**
     * Maximum number of concurrent SSE subscribers per session.
     *
     * <p>2× {@link GameSession#MAXIMUM_PLAYERS} (6) = 12, allowing reconnect churn
     * where the old connection has not yet been garbage-collected when the client
     * reconnects.
     */
    static final int MAX_SUBSCRIBERS_PER_SESSION = 2 * GameSession.MAXIMUM_PLAYERS;

    /**
     * Maximum total SSE subscribers across all sessions.
     *
     * <p>Hard ceiling to prevent descriptor exhaustion. See ADR-034.
     */
    static final int MAX_TOTAL_SUBSCRIBERS = 500;

    /**
     * Emitter timeout in milliseconds. Matches {@code spring.mvc.async.request-timeout}.
     *
     * <p>A non-zero timeout means the servlet container will close an idle stream after
     * ten minutes, which bounds the maximum time a file descriptor is held by a client
     * that has silently disappeared without the heartbeat noticing.
     */
    static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /**
     * How long a client should wait before reconnecting, in milliseconds.
     *
     * <p>Written once per subscription as the stream's {@code retry:} field. Three
     * seconds is long enough that a restarting container is not hammered and short
     * enough that a facilitator does not narrate the wait.
     */
    private static final long RECONNECT_HINT_MILLIS = 3000L;

    private static final int SEND_POOL_THREAD_COUNT = 4;

    /**
     * Bounded queue capacity for the send pool — approximately two heartbeat sweeps of
     * the maximum subscriber count. Oldest tasks are discarded when the queue is full
     * (a dropped heartbeat is harmless; the next sweep retries).
     */
    private static final int SEND_POOL_QUEUE_CAPACITY = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(SseSessionEventPublisher.class);

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Per-session lock objects, kept in sync with the subscribers map. */
    private final Map<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    private final AtomicInteger totalSubscriberCount = new AtomicInteger();

    private final AtomicInteger sendThreadCounter = new AtomicInteger();

    private final ScheduledExecutorService heartbeats;

    private final ExecutorService sendPool;

    private final Duration heartbeatInterval;

    private final int maxPerSessionSubscribers;

    private final int maxTotalSubscribers;

    @Autowired
    public SseSessionEventPublisher(final RealtimeProperties properties) {
        this(properties, MAX_SUBSCRIBERS_PER_SESSION, MAX_TOTAL_SUBSCRIBERS);
    }

    /**
     * Package-private constructor for tests that need a reduced global cap.
     *
     * @param properties          heartbeat configuration
     * @param maxTotalSubscribers override for the global subscriber ceiling
     */
    SseSessionEventPublisher(final RealtimeProperties properties, final int maxTotalSubscribers) {
        this(properties, MAX_SUBSCRIBERS_PER_SESSION, maxTotalSubscribers);
    }

    /**
     * Package-private constructor for tests that need a reduced per-session and/or global cap.
     *
     * @param properties             heartbeat configuration
     * @param maxPerSessionSubscribers override for the per-session subscriber ceiling
     * @param maxTotalSubscribers    override for the global subscriber ceiling
     */
    SseSessionEventPublisher(final RealtimeProperties properties,
            final int maxPerSessionSubscribers, final int maxTotalSubscribers) {
        Objects.requireNonNull(properties, "properties is required");
        this.heartbeatInterval = properties.heartbeatInterval();
        this.maxPerSessionSubscribers = maxPerSessionSubscribers;
        this.maxTotalSubscribers = maxTotalSubscribers;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.sendPool = new ThreadPoolExecutor(
                SEND_POOL_THREAD_COUNT,
                SEND_POOL_THREAD_COUNT,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SEND_POOL_QUEUE_CAPACITY),
                r -> {
                    final Thread thread = new Thread(r, "sse-send-" + sendThreadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        final long period = this.heartbeatInterval.toMillis();
        this.heartbeats.scheduleAtFixedRate(this::beat, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a new listener for one session and returns its open stream.
     *
     * <p>A per-session cap of {@value #MAX_SUBSCRIBERS_PER_SESSION} and a global cap of
     * {@value #MAX_TOTAL_SUBSCRIBERS} are checked before the emitter is created. If either
     * is exceeded, {@link TooManySubscribersException} is thrown and no emitter is allocated.
     *
     * <p>The emitter uses a timeout of {@value #EMITTER_TIMEOUT_MILLIS} ms (10 minutes), matching
     * {@code spring.mvc.async.request-timeout}. A non-zero timeout means the container will clean
     * up a stale connection even if the heartbeat sweep has not yet written to it.
     *
     * @param sessionId the session whose changes should be delivered
     * @return the stream to return from a controller method
     * @throws TooManySubscribersException if the per-session or global cap is already reached
     */
    public SseEmitter subscribe(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        // Get or create the lock first — this is safe because we never remove entries
        // from sessionLocks, so the identity of the lock object is stable forever.
        final Object lock = sessionLocks.computeIfAbsent(sessionId, key -> new Object());

        final SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);

        synchronized (lock) {
            // Get or create the subscriber list inside the lock — also never removed,
            // so no orphan window where a list is added after publish()/beat() checked.
            final CopyOnWriteArrayList<SseEmitter> forSession =
                    subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());

            if (forSession.size() >= maxPerSessionSubscribers) {
                throw new TooManySubscribersException(
                        "Session " + sessionId + " already has too many subscribers");
            }
            int current;
            do {
                current = totalSubscriberCount.get();
                if (current >= maxTotalSubscribers) {
                    throw new TooManySubscribersException("Subscriber limit reached");
                }
            } while (!totalSubscriberCount.compareAndSet(current, current + 1));

            emitter.onCompletion(() -> forgetOne(forSession, emitter));
            emitter.onTimeout(() -> forgetOne(forSession, emitter));
            emitter.onError(failure -> forgetOne(forSession, emitter));
            forSession.add(emitter);
        }

        try {
            emitter.send(SseEmitter.event()
                    .reconnectTime(RECONNECT_HINT_MILLIS)
                    .comment("subscribed"));
        }
        catch (final IOException | IllegalStateException gone) {
            forgetOne(subscribers.get(sessionId), emitter);
        }
        return emitter;
    }

    @Override
    public void publish(final SessionEvent event) {
        Objects.requireNonNull(event, "event is required");

        final CopyOnWriteArrayList<SseEmitter> forSession = subscribers.get(event.sessionId());
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
                forgetOne(forSession, emitter);
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
        final CopyOnWriteArrayList<SseEmitter> forSession = subscribers.get(sessionId);
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
        sessionLocks.clear();
        totalSubscriberCount.set(0);
    }

    @Override
    public void destroy() throws Exception {
        heartbeats.shutdownNow();
        heartbeats.awaitTermination(5, TimeUnit.SECONDS);
        subscribers.values().forEach(forSession -> forSession.forEach(this::completeQuietly));
        subscribers.clear();
        sessionLocks.clear();
        totalSubscriberCount.set(0);
        sendPool.shutdownNow();
        sendPool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Writes a comment frame to every open stream.
     *
     * <p>The frame carries no data and no event name, so a client ignores it. Its
     * only purpose is to be a write: a write is the only thing that discovers a
     * connection the peer has already abandoned, and it also keeps intermediaries
     * from closing a quiet lobby as idle.
     *
     * <p>Per-emitter sends are submitted to {@code sendPool} so that a slow reader
     * cannot block the heartbeat thread and stall the entire sweep. The heartbeat
     * thread submits tasks and returns immediately; it does not wait for them to
     * complete (EOP-20, ADR-034).
     *
     * <p>The whole body is guarded, because an exception escaping a scheduled task
     * cancels the schedule silently — the failure mode would be heartbeats simply
     * stopping, which is precisely the condition they exist to detect.
     */
    private void beat() {
        try {
            subscribers.forEach((sessionId, forSession) -> {
                for (final SseEmitter emitter : forSession) {
                    sendPool.submit(() -> {
                        try {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                        }
                        catch (final IOException | IllegalStateException gone) {
                            forgetOne(forSession, emitter);
                        }
                    });
                }
            });
        }
        catch (final RuntimeException unexpected) {
            LOG.warn("Heartbeat sweep failed; streams remain open and the next sweep will retry", unexpected);
        }
    }

    private void forgetOne(final CopyOnWriteArrayList<SseEmitter> list,
            final SseEmitter emitter) {
        if (list != null && list.remove(emitter)) {
            totalSubscriberCount.decrementAndGet();
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
