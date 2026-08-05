package org.maglez.eop.usecase;

import java.util.ArrayList;
import java.util.List;

/**
 * Session event publisher that remembers what it was asked to announce.
 *
 * <p>Whether an event was published, and how many times, is the whole contract from
 * the use case's point of view — the real publisher's fan-out is tested separately.
 * The optional shared log lets a test assert that the database write happened before
 * the announcement, which matters because a subscriber told about a start before the
 * row moved could re-read the old status.
 */
final class RecordingSessionEventPublisher implements SessionEventPublisher {

    private final List<SessionEvent> published = new ArrayList<>();
    private final List<String> interactions;

    RecordingSessionEventPublisher() {
        this(new ArrayList<>());
    }

    /**
     * Creates the fake sharing an interaction log with another fake.
     *
     * @param interactions the log to append port names to
     */
    RecordingSessionEventPublisher(final List<String> interactions) {
        this.interactions = interactions;
    }

    @Override
    public void publish(final SessionEvent event) {
        interactions.add("publish");
        published.add(event);
    }

    /**
     * @return the events announced, in order
     */
    List<SessionEvent> published() {
        return List.copyOf(published);
    }
}
