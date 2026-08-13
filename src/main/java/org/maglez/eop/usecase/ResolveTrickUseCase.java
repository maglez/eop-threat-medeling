package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.HandNotDealtException;
import org.maglez.eop.entity.NoTrickToResolveException;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickAlreadyResolvedException;
import org.maglez.eop.entity.TrickNotCompleteException;
import org.maglez.eop.entity.WinningPlayNotInTrickException;

/**
 * Resolves the current trick: decides which play took it, records the winner and moves the lead to
 * whoever plays first next.
 *
 * <p>Any seated player may ask for this, not only the facilitator. Resolution is a mechanical
 * consequence of a complete trick rather than a decision anybody makes — the winner is already
 * determined by the cards on the table the moment the last one is played — so gating it on the
 * facilitator would stall the table for everyone if that one person's connection drops. Membership
 * is still required, because who leads next is not information a stranger is owed.
 *
 * <p>Authorising the requester is the first statement here, as it is in every use case that touches
 * this data. Neither port takes an acting player, and the refusals underneath answer one of five
 * distinguishable states — the session exists, its status, whether hands are dealt, which seat leads
 * — which is the right answer for a member and an oracle for anyone else (ADR-024). Only this layer
 * can tell the two callers apart.
 *
 * <p>Three states that are not a complete trick are answered by three different types, because a
 * client that wants to say "waiting for the deal", "waiting for the lead" or "waiting for Dana"
 * cannot tell them apart from one shared type: {@link HandNotDealtException},
 * {@link NoTrickToResolveException} and {@link TrickNotCompleteException}. All three are 409 and all
 * three are worth retrying once the table has moved on.
 *
 * <p>The already-resolved check is made here rather than left to the domain. {@link Trick#resolved()}
 * answers a second resolution with an {@link IllegalStateException}, which would reach a caller as a
 * 500 for what is an ordinary duplicate request — two clients watching the same complete trick both
 * asking to resolve it is a race that happens every hand, not a fault. The port's own
 * {@link TrickAlreadyResolvedException} still stands behind this one for the narrower race where the
 * winner is recorded between this read and that write (ADR-020); this check is what keeps the
 * common case out of the logs.
 *
 * <p>The {@link WinningPlayNotInTrickException} guard before the write is unreachable through
 * {@link Trick#resolved()} today, because the trick's own constructor already refuses a winner that
 * is not among its plays. It is written anyway because it is the only check that will ever exist on
 * this path: {@code fk_trick_winner_play} confines the winning play to the {@code trick_play} table
 * and nothing more, so a play from another trick — or another session — satisfies the schema, and the
 * composite key that would confine it cannot be expressed in Liquibase (ADR-023, ADR-024). The day a
 * winner arrives from anywhere other than that constructor, this is what refuses it.
 *
 * <p>When the hand is spent, {@link Trick#nextLeaderSeat} answers empty: no seat holds a card, so
 * nobody leads next. The port takes an {@code int} and rejects anything outside the seat range, so
 * there is no value for "nobody" to send, and this use case sends the winning seat. That is harmless
 * because no seat holds a card and so no play can follow it, and it is preferable to reopening a
 * port, an adapter and their tests that shipped in the previous slice. Recognising that the hand is
 * over — stopping the next trick from being opened at all, and declaring the hand finished — is the
 * next slice's work, and until it lands the seat written here is a placeholder that nothing reads.
 *
 * <p>The resolved trick is returned because everything in it is public: every card in it was played
 * face up and the winner is what the whole table is waiting to see.
 */
public class ResolveTrickUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;

    private final HandRepository handRepository;

    private final TrickRepository trickRepository;

    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase resolves the acting player from the identity token
     * @param handRepository reads the hands, which say which seats still hold cards
     * @param trickRepository reads the current trick and records its resolution
     * @param clock supplies the resolution timestamp
     */
    public ResolveTrickUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final Clock clock) {
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Resolves the session's current trick.
     *
     * @param sessionId the session whose trick is to be resolved
     * @param playerToken the requester's identity token, which may be null or unrecognised
     * @return the trick with its winner decided
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if the token names nobody at this table
     * @throws HandNotDealtException if the hands have not been dealt
     * @throws NoTrickToResolveException if no trick has been opened yet
     * @throws TrickNotCompleteException if a seat holding cards has not played into the trick
     * @throws TrickAlreadyResolvedException if the trick already has a winner
     * @throws WinningPlayNotInTrickException if the winning play was not made into this trick
     * @throws SessionNotJoinableException if the session is no longer playable
     */
    public Trick execute(final UUID sessionId, final String playerToken) {
        resolvePlayerUseCase.execute(sessionId, playerToken);

        final var hands = handRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new HandNotDealtException(sessionId));
        final var trick = trickRepository.findCurrentTrick(sessionId)
                .orElseThrow(() -> new NoTrickToResolveException(sessionId));

        if (trick.winner().isPresent()) {
            throw new TrickAlreadyResolvedException(trick.trickId());
        }

        final var seatsHoldingCards = hands.seatsHoldingCards();
        if (!trick.isComplete(seatsHoldingCards)) {
            throw new TrickNotCompleteException(trick.trickId(),
                    trick.seatToPlay(seatsHoldingCards).orElse(trick.leaderSeat()));
        }

        final var resolved = trick.resolved();
        final var winner = resolved.winner()
                .orElseThrow(() -> new IllegalStateException("Trick " + trick.trickId() + " resolved to no winner"));
        final var winnerWasPlayedHere = resolved.plays().stream()
                .anyMatch(play -> play.trickPlayId().equals(winner.trickPlayId()));
        if (!winnerWasPlayedHere) {
            throw new WinningPlayNotInTrickException(trick.trickId(), winner.trickPlayId());
        }

        final var nextLeaderSeat = resolved.nextLeaderSeat(seatsHoldingCards).orElse(resolved.winningSeat());
        trickRepository.recordResolution(sessionId, resolved, trick.leaderSeat(), nextLeaderSeat, clock.instant());

        return resolved;
    }
}
