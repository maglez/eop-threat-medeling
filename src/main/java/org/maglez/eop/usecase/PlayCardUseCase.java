package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import org.maglez.eop.entity.CardNotFoundException;
import org.maglez.eop.entity.HandCompleteException;
import org.maglez.eop.entity.HandNotDealtException;
import org.maglez.eop.entity.OutOfTurnException;
import org.maglez.eop.entity.PlayerNotInSessionException;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickPlay;

/**
 * Plays one card from the acting player's hand into the current trick, opening a trick first if none
 * is open.
 *
 * <p>Opening lives here because the lead is not a separate act at the table: the first card played
 * is what opens a trick. A separate open-a-trick call would create a state nobody asked for &mdash;
 * an open trick with no plays in it &mdash; and would need its own authorisation and its own
 * refusals for no gain.
 *
 * <p>Authorisation is the first <em>port call</em> of {@link #execute}, before any read of the hands
 * and before any write; only a null check on the command and the read of its session identifier
 * precede it, and neither touches a port or the table's state. Neither {@link HandRepository} nor
 * {@link TrickRepository} takes an acting
 * player, so this is the only place it can happen, and the refusals below it are informative enough
 * to describe the table's state to whoever asks (ADR-024). The acting seat is then taken from the
 * resolved player and nowhere else: {@link PlayCardCommand} cannot carry one.
 *
 * <p>The card is looked up twice, deliberately, against two independent authorities. The deck row
 * supplies the suit and rank, so a caller cannot assert them; the hand is then asked to resolve that
 * card, so a card the player does not hold is refused whatever the deck says about it. Both lookups
 * have to agree before anything is written.
 *
 * <p>The second of those lookups is run once here, before {@link TrickRepository#openTrick}, and then
 * again inside {@link Trick#acceptPlay} which remains the sole authority on legality. The
 * duplication buys ordering, not safety: opening commits a row, and if the play were refused
 * afterwards the session would be left holding an open trick that nobody can play into.
 *
 * <p>A hand that has been played out is refused before any of that, by name. Every hand is empty
 * once the last trick is resolved, and each of the three checks below it would otherwise answer the
 * same state with something untrue: {@link org.maglez.eop.entity.Hand#resolve} reports a card the
 * player does not hold, which is a 422 saying the caller named the wrong card when there is no card
 * left to name; {@link HandRepository#findCurrentLeaderSeat} finds no leader recorded and cannot tell
 * a spent hand from an undealt one; and {@link Trick#assertSeatMayPlay} answers a trick opened on a
 * seat holding no cards with an {@link IllegalStateException}, a 500 for an ordinary end of play.
 * {@link HandCompleteException} is checked here so the honest answer arrives first, and it is checked
 * after the seat, because who the caller is settles before what the table is doing.
 *
 * <p>Turn order is pre-flighted for the same reason, and it has to be a separate check: a player who
 * genuinely holds the card but is not the one to lead passes the resolve above, so without this guard
 * {@link TrickRepository#openTrick} would commit a trick row and only then would
 * {@link Trick#acceptPlay} refuse the play &mdash; leaving behind an open, empty trick nobody asked
 * for and whose leader seat is not the refused caller's. On the opening path the seat to play is by
 * definition the recorded leader seat, so comparing the two is exact rather than approximate.
 * {@link Trick#acceptPlay} still re-checks it, and still owns the answer once a trick is under way,
 * where whose turn it is depends on the plays already made rather than on the leader alone.
 *
 * <p>For the same reason the candidate play is built before {@link TrickRepository#openTrick} is
 * called rather than after it. {@link TrickPlay} is where the bounds on caller supplied text live
 * &mdash; at most twenty components, two hundred characters each, two thousand characters of notes,
 * and no control or bidirectional formatting characters &mdash; and {@link PlayCardCommand}
 * deliberately does not repeat them, because one place that enforces an invariant is easier to trust
 * than two that have to agree. Opening the trick first would commit a row and only then reject an
 * over-long note, which is the same orphan trick the two guards above exist to prevent arriving by a
 * different door. Building the play first is what makes every refusal on an opening lead precede the
 * write, rather than only the two that were thought of first.
 *
 * <p>The leader seat handed to every write comes from the trick that was read or from
 * {@link HandRepository#findCurrentLeaderSeat}, never from the caller. It is the compare-and-set
 * witness that serialises concurrent plays (ADR-020), and it advances once per trick rather than
 * once per play, so opening passes the same value it read. Whose turn it is <em>within</em> a trick
 * is derived by {@link Trick} from the plays already made.
 *
 * <p>What is persisted is the play as {@link Trick#acceptPlay} returned it, carrying the card as
 * dealt rather than as claimed. The updated trick is returned because a played card is face up on
 * the table; nothing in it is private.
 *
 * <p>Once the play is appended, {@code card-played} is announced through
 * {@link SessionEventPublisher}. The announcement is made after the write returns, so a refused play
 * announces nothing, and it carries no part of the play: {@link SessionEvent} names a type, a session
 * and an instant, leaving every recipient to re-read the state of play for itself (ADR-014). That is
 * what keeps one producer of the answer rather than two that can disagree, and it is also why the
 * caller is told whose turn it is by that read rather than by this response. Publishing is not
 * guarded here because it must not fail a request &mdash; an obligation
 * {@link SessionEventPublisher} places on its implementation &mdash; and delivery is unordered with
 * respect to this response, so a caller may be notified of its own play.
 *
 * <p>A complete trick is not resolved here. Resolution is {@link ResolveTrickUseCase}'s, and a play
 * that silently resolved would make one caller's refusal depend on another caller's timing.
 */
public class PlayCardUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;
    private final HandRepository handRepository;
    private final TrickRepository trickRepository;
    private final CardRepository cardRepository;
    private final IdentifierGenerator identifierGenerator;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase resolves the identity token into a seated player
     * @param handRepository reads the hands and the recorded leader seat
     * @param trickRepository opens tricks and appends plays
     * @param cardRepository resolves the played card against the deck
     * @param identifierGenerator mints trick and play identifiers
     * @param sessionEventPublisher announces that a card was played, naming none of it
     * @param clock supplies the instant the play was made at
     */
    public PlayCardUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final CardRepository cardRepository,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        this.resolvePlayerUseCase =
                Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository =
                Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.sessionEventPublisher =
                Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Plays the named card for the player the token identifies.
     *
     * @param command what the player asked to play
     * @return the trick with this play appended
     * @throws NullPointerException if command is null
     * @throws org.maglez.eop.entity.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token names nobody at this
     *     table, or no token was given
     * @throws HandNotDealtException if this session has not been dealt
     * @throws PlayerNotInSessionException if the acting player holds no hand in this session
     * @throws HandCompleteException if every card dealt in this session has already been played
     * @throws CardNotFoundException if the card identifier names no card in the deck
     * @throws org.maglez.eop.entity.CardNotInHandException if the player does not hold that card
     * @throws org.maglez.eop.entity.OutOfTurnException if it is not this seat's turn
     * @throws org.maglez.eop.entity.MustFollowSuitException if the player holds the led suit and
     *     played something else
     * @throws org.maglez.eop.entity.NotYourSeatException if the play does not belong to the acting
     *     seat
     * @throws org.maglez.eop.entity.PlayerMismatchException if the seat is held by another player
     * @throws org.maglez.eop.entity.AlreadyPlayedInTrickException if this seat has already played
     *     into this trick
     * @throws org.maglez.eop.entity.CardAlreadyPlayedException if that card was already played
     * @throws org.maglez.eop.entity.TrickAlreadyOpenException if another request opened a trick first
     * @throws org.maglez.eop.entity.SessionNotJoinableException if the session was not playable at
     *     the moment of the write
     */
    public Trick execute(final PlayCardCommand command) {
        Objects.requireNonNull(command, "command is required");
        final var sessionId = command.sessionId();

        final var resolved = resolvePlayerUseCase.execute(sessionId, command.playerToken());
        final var actingSeat = resolved.player().seatOrder();
        final var actingPlayerId = resolved.player().playerId();

        final var hands =
                handRepository
                        .findBySessionId(sessionId)
                        .orElseThrow(() -> new HandNotDealtException(sessionId));
        if (!hands.hasSeat(actingSeat)) {
            throw new PlayerNotInSessionException(sessionId);
        }
        if (hands.allEmpty()) {
            throw new HandCompleteException(sessionId);
        }
        final var hand = hands.handOf(actingSeat);

        final var card =
                cardRepository
                        .findById(command.cardId())
                        .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        hand.resolve(card);

        final var leaderSeat =
                handRepository
                        .findCurrentLeaderSeat(sessionId)
                        .orElseThrow(() -> new HandNotDealtException(sessionId));
        final var now = clock.instant();
        final var current = trickRepository.findCurrentTrick(sessionId);

        final var opening = current.isEmpty() || current.get().winner().isPresent();
        final Trick trick;
        if (opening) {
            if (actingSeat != leaderSeat) {
                throw new OutOfTurnException(leaderSeat, actingSeat);
            }
            trick =
                    Trick.open(
                            identifierGenerator.nextIdentifier(),
                            current.map(resolvedTrick -> resolvedTrick.sequence() + 1).orElse(1),
                            leaderSeat);
        }
        else {
            trick = current.get();
        }

        final var candidate =
                new TrickPlay(
                        identifierGenerator.nextIdentifier(),
                        actingPlayerId,
                        actingSeat,
                        card,
                        command.threatLinked(),
                        command.components(),
                        command.notes(),
                        now);

        if (opening) {
            trickRepository.openTrick(sessionId, trick, leaderSeat, now);
        }

        final var updated = trick.acceptPlay(actingSeat, candidate, hands);
        final var accepted = updated.plays().getLast();

        trickRepository.appendPlay(sessionId, trick.trickId(), leaderSeat, accepted);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.CARD_PLAYED, sessionId, now));
        return updated;
    }
}
