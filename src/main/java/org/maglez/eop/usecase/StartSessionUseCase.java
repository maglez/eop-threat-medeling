package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameSession;

/**
 * Closes a lobby and puts the session into play.
 *
 * <p>Starting a session establishes that the lobby is closed, and nothing more. No
 * cards are dealt here: dealing is EOP-14, and putting it behind the same call would
 * make two very different failures indistinguishable to the caller.
 *
 * <p>The endpoint exists in this story because without it {@code IN_PROGRESS} would be
 * unreachable through the API, and the rule that a late join is refused could only be
 * tested against a fabricated database fixture (ADR-019).
 */
public class StartSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to record the transition
     * @param resolvePlayerUseCase the use case that turns a token into a seated player
     * @param sessionEventPublisher the port that announces the start
     * @param clock the clock used to stamp the transition, injected so tests are
     *     deterministic
     */
    public StartSessionUseCase(
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
     * Moves a session from lobby into play at its facilitator's request.
     *
     * <p>Every rule applied here — that the caller is seated, that the caller is the
     * facilitator, that the lobby has not already been closed, and that three players
     * are present — is decided by {@link GameSession}. This method only supplies the
     * clock and persists the outcome.
     *
     * @param sessionId the session being started
     * @param playerToken the caller's identity token, as received in the request header
     * @return the session as it stands once play has begun
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names no
     *     player seated in that session
     * @throws org.maglez.eop.entity.NotFacilitatorException if the caller is a participant
     * @throws org.maglez.eop.entity.SessionNotJoinableException if the lobby is already closed
     * @throws org.maglez.eop.entity.TooFewPlayersException if fewer than three players are seated
     */
    public GameSession execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var now = clock.instant();
        final var started = resolved.session().start(resolved.player().playerId(), now);

        sessionRepository.recordStarted(started.sessionId(), now);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.GAME_STARTED, started.sessionId(), now));
        return started;
    }
}
