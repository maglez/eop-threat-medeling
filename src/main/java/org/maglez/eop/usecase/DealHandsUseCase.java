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
import org.maglez.eop.entity.TooFewPlayersException;

/**
 * Deals the whole deck to the seated players and records the opening lead.
 *
 * <p>This is a use case in its own right rather than a step inside {@link StartSessionUseCase},
 * because the two writes cannot share a transaction through these ports and should not pretend to.
 * {@link HandRepository#recordDeal} refuses a session that had not started at the moment of the
 * write, so the status transition has to be committed first; wrapping both in one transaction would
 * put {@code org.springframework.transaction} into this layer, which AGENTS.md forbids. Keeping them
 * apart also keeps their failures apart: "you are not the facilitator" and "there are only two of
 * you" are different answers from "the deal has already happened", and a caller that receives them
 * through one call cannot tell which write it needs to retry.
 *
 * <p>Dealing twice is not a hazard that this class defends against. {@code recordDeal} writes the
 * opening leader seat only where none is recorded, so two facilitators pressing at once produce one
 * deal and one refusal, decided by the database rather than by a check up here. A status or
 * already-dealt pre-check in this method would read the state, let go of it, and then write &mdash;
 * an illusion of safety that duplicates the only check that actually holds (ADR-020). What this
 * class owes the port instead is the thing the port cannot do for itself: establishing who is
 * asking, before anything else happens.
 *
 * <p>Authorisation is therefore the first statement in {@link #execute}. {@link HandRepository}
 * takes no acting player and its javadoc says plainly that authorising the requester is the caller's
 * obligation, so this method is the only place it can happen. The refusals underneath it are
 * informative by design &mdash; they distinguish a session that has not started from one already
 * dealt &mdash; which is the right answer for a member of the table and an information leak for
 * anybody else (ADR-024).
 *
 * <p>The seat count is checked here as well, because {@link Hands#deal} answers too few players with
 * an {@link IllegalArgumentException}: correct for a programming error, but a 500 for what is really
 * an ordinary "wait for one more player". {@link TooFewPlayersException} says so with a 409, and the
 * domain's own check remains behind it.
 *
 * <p>Nothing is returned. A result carrying every hand is exactly the shape that leaks private
 * information &mdash; the reason {@link Hands#toString()} names no card &mdash; and a facilitator
 * has no more right to see the table's cards than anyone else. Each player reads their own hand
 * through their own query.
 *
 * <p>Shuffling happens here, through {@link DeckShuffler}, because {@link Hands#deal} is
 * deliberately a pure function of an ordered deck (ADR-023).
 */
public class DealHandsUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final CardRepository cardRepository;
    private final DeckShuffler deckShuffler;
    private final HandRepository handRepository;
    private final IdentifierGenerator identifierGenerator;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase resolves the identity token into a seated player
     * @param cardRepository reads the whole deck
     * @param deckShuffler randomises the deck before it is dealt
     * @param handRepository records the deal and the opening lead
     * @param identifierGenerator mints one hand identifier per seat
     * @param clock supplies the instant the deal is recorded at
     */
    public DealHandsUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final CardRepository cardRepository,
            final DeckShuffler deckShuffler,
            final HandRepository handRepository,
            final IdentifierGenerator identifierGenerator,
            final Clock clock) {
        this.resolvePlayerUseCase =
                Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
        this.deckShuffler = Objects.requireNonNull(deckShuffler, "deckShuffler is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Deals the deck to every seated player and records which seat leads the first trick.
     *
     * @param sessionId the session whose players are dealt to
     * @param playerToken the identity token of the player asking to deal
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.entity.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names nobody at this
     *     table, or no token was given
     * @throws NotFacilitatorException if the caller is seated but is not the facilitator
     * @throws TooFewPlayersException if fewer than {@link GameSession#MINIMUM_PLAYERS_TO_START}
     *     players are seated
     * @throws org.maglez.eop.entity.SessionNotJoinableException if the session was not playable at
     *     the moment of the write, which includes a session that had not yet started
     * @throws org.maglez.eop.entity.HandAlreadyDealtException if this session has already been dealt
     * @throws org.maglez.eop.entity.PlayerNotInSessionException if a seat named in the deal is not
     *     seated in this session
     * @throws org.maglez.eop.entity.NotYourSeatException if a player named in the deal does not hold
     *     the seat the deal gives them
     * @throws NoTamperingCardDealtException if the shuffled deck contained no Tampering card, so no
     *     opening lead can be derived
     */
    public void execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        final var session = resolved.session();
        final var actingPlayer = resolved.player();

        if (!actingPlayer.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, actingPlayer.playerId());
        }

        final var seated = session.players().size();
        if (seated < GameSession.MINIMUM_PLAYERS_TO_START) {
            throw new TooFewPlayersException(
                    sessionId, seated, GameSession.MINIMUM_PLAYERS_TO_START);
        }

        final List<Hands.Seat> seats =
                session.players().stream()
                        .sorted(Comparator.comparingInt(player -> player.seatOrder()))
                        .map(
                                player ->
                                        new Hands.Seat(
                                                player.seatOrder(),
                                                player.playerId(),
                                                identifierGenerator.nextIdentifier()))
                        .toList();

        final var hands = Hands.deal(deckShuffler.shuffle(cardRepository.findWholeDeck()), seats);

        handRepository.recordDeal(
                sessionId, hands, hands.openingLeaderSeat(), clock.instant());
    }
}
