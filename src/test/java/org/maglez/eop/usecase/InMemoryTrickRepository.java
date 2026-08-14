package org.maglez.eop.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickPlay;

/**
 * A trick repository that keeps one session's current trick in memory.
 *
 * <p>Hand written for the same reason as {@link InMemoryHandRepository}, and
 * with the same deliberate omission: the compare-and-set on the recorded leader
 * seat that every write on this port carries is not reproduced here. Whether a
 * conditional update matches a row is a question only a database can answer, so
 * it is settled in the adapter's integration tests. What a use case test can
 * settle is which witness the use case passed down — and passing the wrong one,
 * or passing one taken from a request body, is the defect this double is shaped
 * to expose.
 *
 * <p>Each write records its arguments and appends its name to the shared call
 * log, so a test can assert both that a trick was opened before a play was
 * appended and that no trick was opened at all when the play was refused.
 */
final class InMemoryTrickRepository implements TrickRepository {

    /**
     * One recorded resolution.
     *
     * @param trick the resolved trick, as the domain returned it
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param nextLeaderSeat the seat the use case said should lead next
     * @param occurredAt the instant the use case supplied
     */
    record Resolution(Trick trick, int expectedLeaderSeat, int nextLeaderSeat, Instant occurredAt) {
    }

    /**
     * One recorded appended play.
     *
     * @param trickId the trick the play was appended to
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param play the play, as {@code acceptPlay} returned it
     */
    record Appended(UUID trickId, int expectedLeaderSeat, TrickPlay play) {
    }

    /**
     * One recorded opened trick.
     *
     * @param trick the trick as the use case built it
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param occurredAt the instant the use case supplied
     */
    record Opened(Trick trick, int expectedLeaderSeat, Instant occurredAt) {
    }

    private final List<String> order;

    private final List<Opened> opened = new ArrayList<>();

    private final List<Appended> appended = new ArrayList<>();

    private final List<Resolution> resolutions = new ArrayList<>();

    private Trick current;

    /**
     * Creates an empty repository, as a session looks before its first lead.
     *
     * @param order the shared call log this repository appends its writes to
     */
    InMemoryTrickRepository(final List<String> order) {
        this.order = Objects.requireNonNull(order, "order is required");
    }

    /**
     * Seeds the trick that reads should answer with.
     *
     * @param trick the current trick, resolved or not
     * @return this repository
     */
    InMemoryTrickRepository seededWith(final Trick trick) {
        this.current = Objects.requireNonNull(trick, "trick is required");
        return this;
    }

    @Override
    public Optional<Trick> findCurrentTrick(final UUID sessionId) {
        return Optional.ofNullable(current);
    }

    @Override
    public void openTrick(final UUID sessionId, final Trick trick, final int expectedLeaderSeat,
            final Instant occurredAt) {
        order.add("openTrick");
        opened.add(new Opened(trick, expectedLeaderSeat, occurredAt));
        current = trick;
    }

    @Override
    public void appendPlay(final UUID sessionId, final UUID trickId, final int expectedLeaderSeat,
            final TrickPlay play) {
        order.add("appendPlay");
        appended.add(new Appended(trickId, expectedLeaderSeat, play));
    }

    @Override
    public void recordResolution(final UUID sessionId, final Trick resolved, final int expectedLeaderSeat,
            final int nextLeaderSeat, final Instant occurredAt) {
        order.add("recordResolution");
        resolutions.add(new Resolution(resolved, expectedLeaderSeat, nextLeaderSeat, occurredAt));
        current = resolved;
    }

    /**
     * Answers every trick this repository was asked to open.
     *
     * <p>Empty is the assertion that matters most: a refusal that has already
     * opened a trick has left an empty trick behind that nobody asked for, and
     * only this list can tell that apart from a clean refusal.
     *
     * @return the opened tricks, in the order they were opened
     */
    List<Opened> opened() {
        return List.copyOf(opened);
    }

    /**
     * Answers every play this repository was asked to append.
     *
     * @return the appended plays, in the order they were appended
     */
    List<Appended> appended() {
        return List.copyOf(appended);
    }

    /**
     * Answers every resolution this repository was asked to record.
     *
     * @return the resolutions, in the order they were recorded
     */
    List<Resolution> resolutions() {
        return List.copyOf(resolutions);
    }
}
