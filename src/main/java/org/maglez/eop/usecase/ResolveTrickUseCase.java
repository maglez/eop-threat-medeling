package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.HandNotDealtException;
import org.maglez.eop.entity.NoTrickToResolveException;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionNotInProgressException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickAlreadyResolvedException;
import org.maglez.eop.entity.TrickNotCompleteException;
import org.maglez.eop.entity.WinningPlayNotInTrickException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * nobody leads next, and that absence is passed straight to the port as an empty
 * {@link java.util.OptionalInt}. Slice D sent the winning seat instead, because the port took an
 * {@code int} and had no value meaning "nobody"; it was harmless only in the sense that no seat could
 * act on it, and it left the session row asserting that a seat led when that seat held nothing to
 * lead with. The port now records the absence, which is what makes the end of a hand a fact the
 * database states rather than one every reader has to re-derive.
 *
 * <p>Note which seats the lead is chosen from. {@code hands} is read after the plays in this trick
 * removed their cards, so {@code seatsHoldingCards} is the set as it stands once the trick is over,
 * and the winner is only handed the lead if it appears in that set. A winner that has just played its
 * last card passes the lead clockwise to the next seat that still holds one — handing it to a
 * card-less seat would open a trick nobody could legally play into, and the table would stop with no
 * exception raised and nothing logged (ADR-023).
 *
 * <p>When the last trick resolves — {@link Trick#nextLeaderSeat} answers empty because no seat holds
 * a card — the session is automatically transitioned to
 * {@link org.maglez.eop.entity.SessionStatus#COMPLETED} and a {@code game-completed} event is
 * published. The transition is a compare-and-swap on {@code IN_PROGRESS}: if the facilitator's
 * end-session call wins the race in the window between {@code recordResolution} committing and
 * {@code recordCompleted} being called, the CAS finds zero rows and throws
 * {@link SessionNotInProgressException}. The auto-complete branch catches that exception and treats
 * it as success — the session is already {@code COMPLETED}, which is the desired outcome, and the
 * trick resolution itself was already durably committed. The two writes are in separate transactions
 * (each adapter method carries its own {@code @Transactional}), so the race is real and the
 * tolerance is necessary (EOP-15 Slice C, ADR-032).
 *
 * <p>The resolved trick is returned because everything in it is public: every card in it was played
 * face up and the winner is what the whole table is waiting to see.
 *
 * <p>{@code trick-resolved} is announced once the resolution is recorded, so a refused resolution
 * announces nothing and the winner is never announced twice by two callers racing to resolve the same
 * trick — the second is refused before it reaches the write. The announcement carries no part of the
 * outcome: {@link SessionEvent} names a type, a session and an instant, and every recipient re-reads
 * the state of play for itself, which is also how it learns which seat leads next (ADR-014). It is
 * published rather than returned to the resolving caller alone because the seat that leads next is
 * usually somebody else's news. Publishing is not guarded here because it must not fail a request, an
 * obligation {@link SessionEventPublisher} places on its implementation.
 */
public class ResolveTrickUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ResolveTrickUseCase.class);

    private final ResolvePlayerUseCase resolvePlayerUseCase;

    private final HandRepository handRepository;

    private final TrickRepository trickRepository;

    private final SessionRepository sessionRepository;

    private final SessionEventPublisher sessionEventPublisher;

    private final Clock clock;

    private final Optional<PersistGameResultUseCase> persistGameResultUseCase;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase resolves the acting player from the identity token
     * @param handRepository reads the hands, which say which seats still hold cards
     * @param trickRepository reads the current trick and records its resolution
     * @param sessionRepository records the session completion when the last trick resolves
     * @param sessionEventPublisher announces that the trick was resolved, naming none of it
     * @param clock supplies the resolution timestamp
     * @param persistGameResultUseCase persists the final game result; absent when the
     *     {@code game-over} feature flag is off
     */
    public ResolveTrickUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock,
            final Optional<PersistGameResultUseCase> persistGameResultUseCase) {
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.sessionEventPublisher =
                Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.persistGameResultUseCase =
                Objects.requireNonNull(persistGameResultUseCase, "persistGameResultUseCase is required");
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
     * @throws SessionNotInProgressException if the session is not in progress when the auto-complete
     *     CAS is attempted; this is caught and treated as success — the session was already completed
     *     by a concurrent facilitator call
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

        final var nextLeaderSeat = resolved.nextLeaderSeat(seatsHoldingCards);
        final var now = clock.instant();
        trickRepository.recordResolution(sessionId, resolved, trick.leaderSeat(), nextLeaderSeat, now);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.TRICK_RESOLVED, sessionId, now));

        if (nextLeaderSeat.isEmpty()) {
            try {
                sessionRepository.recordCompleted(sessionId, now);
            }
            catch (SessionNotInProgressException ignored) {
                // A concurrent facilitator call already completed the session.
                // The trick resolution was durably committed; treat this as success.
            }
            persistGameResultUseCase.ifPresent(uc -> {
                try {
                    uc.execute(sessionId);
                }
                catch (RuntimeException ex) {
                    // Best-effort: the session is completed and the trick is resolved.
                    // The leaderboard will return 404 until the result is recorded.
                    LOG.warn("[EOP-65] Failed to persist game result for session {}; "
                            + "leaderboard will return 404 until the result is recorded.", sessionId, ex);
                }
            });
            sessionEventPublisher.publish(new SessionEvent(SessionEventType.GAME_COMPLETED, sessionId, now));
        }

        return resolved;
    }
}
