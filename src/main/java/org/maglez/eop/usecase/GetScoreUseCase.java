package org.maglez.eop.usecase;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.ScoreSheet;

/**
 * Reads the score of a session.
 *
 * <p>Nothing is accumulated anywhere. The score is derived, on every read, from the plays and from
 * which play won each trick, so there is no total to drift from the game that produced it and no
 * number a client could assert in place of one (ADR-030). The cost of recomputing is bounded by the
 * deck: seventy-eight cards, whatever the game.
 *
 * <p>Two collaborators, and the first of them supplies two things. Resolving the credential yields
 * both the player and the session it belongs to, and the session already carries its players — so
 * the seated players arrive without a second read, and the only port called here is the one that
 * holds the tricks.
 *
 * <p>Any seated player may read it, and there is nothing to gate. Every card the answer names has
 * already been played face up, and no card that any seat still holds appears in it, which is what
 * separates this from reading a hand. A caller who holds no credential for this session is still
 * refused, as everywhere else: {@code TrickRepository} authorises nobody — no method on it takes an
 * acting player — so this layer is the only place a member is told from a stranger who guessed a
 * session identifier (ADR-024).
 *
 * <p>Membership needs no check beyond resolving the credential. A token is matched only against the
 * players of the session it was presented for, so a token that belongs to a different session is
 * already an unrecognised one here.
 *
 * <p>Deliberately no check on session status, and no requirement that the cards have been dealt.
 * Before the deal the score is everybody on nothing, which is a true answer rather than a missing
 * one, and after play ends looking back over the score is the whole point of having kept it. So
 * unlike the state of play, this read has no state in which it has to refuse.
 *
 * <p>Deciding that a game has <em>ended</em> is not this use case's business either. It reports the
 * score as it stands; moving a session to {@code COMPLETED} is a write, and it belongs to the story
 * that owns that transition.
 *
 * <p>A read, so no clock and no writes. Callers should prefer the event stream and use this to
 * recover, rather than polling it (ADR-014).
 */
public class GetScoreUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;

    private final TrickRepository trickRepository;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player and their session
     * @param trickRepository      the port the session's tricks are read through
     */
    public GetScoreUseCase(final ResolvePlayerUseCase resolvePlayerUseCase, final TrickRepository trickRepository) {
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
    }

    /**
     * Reads the score of the session, as the Score Card would record it.
     *
     * @param sessionId   identifier of the session
     * @param playerToken the caller's identity token, as presented
     * @return the score of the session as it stands
     * @throws NullPointerException                              if {@code sessionId} is {@code null}
     * @throws org.maglez.eop.entity.SessionNotFoundException    if no session has that identifier
     * @throws org.maglez.eop.entity.PlayerNotRecognisedException if the token is missing, or belongs to no player of that session
     * @throws org.maglez.eop.entity.ScoreNotDerivableException  if the stored game contradicts itself
     */
    public ScoreSheet execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        return ScoreSheet.of(resolved.session().players(), trickRepository.findTricks(sessionId));
    }
}
