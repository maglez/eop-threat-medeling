package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameSession;

/**
 * Ends a session early at the facilitator's explicit request.
 *
 * <p>The automatic path — all tricks played — does not go through this use case.
 * It is handled inside {@link ResolveTrickUseCase} when the last trick resolves
 * and {@code nextLeaderSeat} is empty. This use case is for the facilitator's
 * deliberate decision to stop play before every card has been played.
 *
 * <p>Only the facilitator may end a session. Any seated player may resolve a
 * trick (because resolution is mechanical), but ending early is a decision that
 * belongs to the person who opened the lobby.
 *
 * <p>The transition is a compare-and-swap on {@code IN_PROGRESS}: a concurrent
 * call from the automatic path is safe because exactly one will update the row
 * and the other will find zero rows changed (EOP-15 Slice C, ADR-032).
 */
public class EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to record the completion
     * @param resolvePlayerUseCase the use case that turns a token into a seated player
     * @param sessionEventPublisher the port that announces the completion
     * @param clock the clock used to stamp the transition, injected so tests are
     *     deterministic
     */
    public EndSessionUseCase(
            final SessionRepository sessionRepository,
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Moves a session from in-progress to completed at the facilitator's request.
     *
     * <p>Every rule applied here — that the caller is seated, that the caller is the
     * facilitator, and that the session is currently in progress — is decided by
     * {@link GameSession}. This method only supplies the clock and persists the outcome.
     *
     * @param sessionId the session being ended
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as it stands once it is completed
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.entity.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.entity.SessionNotInProgressException if the session is not
     *     currently in progress
     */
    public GameSession execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var now = clock.instant();
        final var completed = resolved.session().complete(resolved.player().playerId(), now);

        sessionRepository.recordCompleted(completed.sessionId(), now);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.GAME_COMPLETED, completed.sessionId(), now));
        return completed;
    }
}
