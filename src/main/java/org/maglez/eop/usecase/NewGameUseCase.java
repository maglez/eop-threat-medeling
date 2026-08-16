package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.Hands;
import org.maglez.eop.entity.NoTamperingCardDealtException;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.TooFewPlayersException;

/**
 * Resets a completed session and re-deals the same deck to the same seats.
 *
 * <p>This is the "Start new game" action available to the facilitator once the leaderboard
 * is shown. It clears all hands and tricks, moves the session back to {@code IN_PROGRESS},
 * and deals a freshly shuffled deck to the same players in the same seats.
 *
 * <p>The sequence is:
 * <ol>
 *   <li>Authorise: caller must be the facilitator of a completed session.</li>
 *   <li>Clear tricks (plays first, then tricks — FK order).</li>
 *   <li>Clear hands (hand-cards first, then hands — FK order).</li>
 *   <li>Reset session status to {@code IN_PROGRESS} and clear the leader seat.</li>
 *   <li>Deal a freshly shuffled deck and record the opening lead.</li>
 * </ol>
 *
 * <p>Steps 2–4 are separate port calls rather than one atomic operation. A partial failure
 * leaves the session in an inconsistent state, but the facilitator can retry: the reset
 * is idempotent once the session is back in {@code IN_PROGRESS} and the deal is the
 * compare-and-set that prevents a double-deal.
 *
 * <p>Pure use case: no Spring, no Jakarta imports.
 */
public class NewGameUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final HandRepository handRepository;
    private final TrickRepository trickRepository;
    private final SessionRepository sessionRepository;
    private final CardRepository cardRepository;
    private final DeckShuffler deckShuffler;
    private final IdentifierGenerator identifierGenerator;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase  resolves the identity token into a seated player
     * @param handRepository        clears and re-records hands
     * @param trickRepository       clears tricks
     * @param sessionRepository     resets session status
     * @param cardRepository        reads the whole deck for re-dealing
     * @param deckShuffler          randomises the deck before it is dealt
     * @param identifierGenerator   mints one hand identifier per seat
     * @param sessionEventPublisher announces that the deal happened
     * @param clock                 supplies timestamps
     */
    public NewGameUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final CardRepository cardRepository,
            final DeckShuffler deckShuffler,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        this.resolvePlayerUseCase =
                Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.sessionRepository =
                Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
        this.deckShuffler = Objects.requireNonNull(deckShuffler, "deckShuffler is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.sessionEventPublisher =
                Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Resets the session and deals a new game.
     *
     * @param sessionId   the completed session to reset
     * @param playerToken the identity token of the player requesting the new game
     * @throws NullPointerException                              if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException   if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names nobody at this table
     * @throws NotFacilitatorException                           if the caller is not the facilitator
     * @throws org.maglez.eop.entity.GameNotCompletedException  if the session is not completed
     * @throws TooFewPlayersException                            if fewer than the minimum players are seated
     * @throws NoTamperingCardDealtException                     if the shuffled deck contained no Tampering card
     */
    public void execute(final UUID sessionId, final String playerToken) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var session = resolved.session();
        final var actingPlayer = resolved.player();

        if (!actingPlayer.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, actingPlayer.playerId());
        }

        if (session.status() != org.maglez.eop.entity.SessionStatus.COMPLETED) {
            throw new org.maglez.eop.entity.GameNotCompletedException(sessionId);
        }

        final var seated = session.players().size();
        if (seated < GameSession.MINIMUM_PLAYERS_TO_START) {
            throw new TooFewPlayersException(sessionId, seated, GameSession.MINIMUM_PLAYERS_TO_START);
        }

        final var now = clock.instant();

        // Clear game state in FK-safe order: plays → tricks → hand-cards → hands
        trickRepository.clearTricksForNewGame(sessionId);
        handRepository.clearHandsForNewGame(sessionId);

        // Reset session to IN_PROGRESS with no leader seat
        sessionRepository.resetToInProgress(sessionId, now);

        // Re-deal a freshly shuffled deck to the same seats
        final List<Hands.Seat> seats =
                session.players().stream()
                        .sorted(Comparator.comparingInt(Player::seatOrder))
                        .map(player -> new Hands.Seat(
                                player.seatOrder(),
                                player.playerId(),
                                identifierGenerator.nextIdentifier()))
                        .toList();

        final var hands = Hands.deal(deckShuffler.shuffle(cardRepository.findWholeDeck()), seats);
        handRepository.recordDeal(sessionId, hands, hands.openingLeaderSeat(), now);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.HAND_DEALT, sessionId, now));
    }
}
