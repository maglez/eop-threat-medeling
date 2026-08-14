package org.maglez.eop.adapter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.PlayCardCommand;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ReadOwnHandUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to trick play: deal the deck, read your own hand, play a card and
 * resolve the trick once every seat that still holds cards has played.
 *
 * <p>This bean only exists when {@code eop.features.trick-play} is true (ADR-013).
 * That flag is separate from {@code eop.features.session-lifecycle} on purpose, so
 * the lobby can be live while trick play is held back. While it is off there are no
 * handlers for these paths and Spring's own no-handler response answers them, which
 * is already a problem detail. Nothing here branches on the flag at request time.
 *
 * <p><strong>The acting seat is never read from a request.</strong> Every handler
 * takes the caller's credential from the header and passes it to a use case, which
 * resolves it to exactly one seated player (ADR-015). No path variable, query
 * parameter or body field names a seat, a player or a card's suit and rank. Two
 * defects fixed earlier in this story were exactly that: a caller-supplied seat let a
 * player play out of somebody else's hand, and a caller-supplied suit and rank let a
 * forged card take a trick.
 *
 * <p>The credential header is declared {@code required = false} for the same reason
 * as in {@link SessionController}: a missing credential is a refused request, not a
 * malformed one, so the null travels to the use case and a missing and an
 * unrecognised token leave as the same 403.
 *
 * <p>{@link SessionController#PLAYER_TOKEN_HEADER} is referenced rather than copied,
 * and deliberately not lifted into a shared constants class. Such a class would hold
 * one compile-time-constant {@code String} and a private constructor; since the
 * constant is inlined by the compiler and the constructor is never called, JaCoCo
 * would find no covered instruction in it, and the coverage gate admits no
 * per-class exclusions.
 *
 * <p>Nothing here logs. That is not an omission: ADR-026 is {@code Proposed} and has
 * not yet decided where use-case observability lives, and one of the four
 * arrangements it weighs is logging at the web boundary. Adding a logger to this one
 * controller would pick that option in a feature slice, and would break the
 * uniformity that ADR-026 records as the reason the gap is tolerable at all.
 *
 * <p>Descriptions here are deliberately brief: {@code docs/api/openapi.yml} is the
 * contract (ADR-004), springdoc is disabled in production, and prose duplicated in
 * two places is how the two come to disagree.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "trick-play", havingValue = "true")
@Tag(name = "trick-play", description = "Dealing, playing and resolving tricks")
public class TrickController {

    private final DealHandsUseCase dealHandsUseCase;

    private final ReadOwnHandUseCase readOwnHandUseCase;

    private final PlayCardUseCase playCardUseCase;

    private final ResolveTrickUseCase resolveTrickUseCase;

    TrickController(
            final DealHandsUseCase dealHandsUseCase,
            final ReadOwnHandUseCase readOwnHandUseCase,
            final PlayCardUseCase playCardUseCase,
            final ResolveTrickUseCase resolveTrickUseCase) {
        this.dealHandsUseCase = Objects.requireNonNull(dealHandsUseCase, "dealHandsUseCase is required");
        this.readOwnHandUseCase = Objects.requireNonNull(readOwnHandUseCase, "readOwnHandUseCase is required");
        this.playCardUseCase = Objects.requireNonNull(playCardUseCase, "playCardUseCase is required");
        this.resolveTrickUseCase = Objects.requireNonNull(resolveTrickUseCase, "resolveTrickUseCase is required");
    }

    /**
     * Deals the whole deck and sets the opening lead.
     *
     * <p>No body is returned. Each player then reads its own hand, so there is one
     * code path for seeing a hand rather than two that could disagree — and a deal
     * response would otherwise have to either name every hand, which no operation
     * may do, or name only the facilitator's, which would privilege one seat.
     *
     * @param sessionId   the session to deal
     * @param playerToken the caller's credential, absent if it sent none
     */
    @PostMapping("/{sessionId}/deal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deal the deck to the seated players")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "The deck is dealt. No body: a hand is read from its own route."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, an unrecognised one, or a player who is not the facilitator."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "Play has not started, the deck is already dealt, or the table is too small."),
        @ApiResponse(responseCode = "500", description = "The stored deck holds no Tampering card, so no opening lead exists.")
    })
    public void dealHands(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        dealHandsUseCase.execute(sessionId, playerToken);
    }

    /**
     * Reads the caller's own hand.
     *
     * <p>The path is singular because the resource is singular: the one hand the
     * caller is entitled to see. A plural would advertise a collection of every
     * hand, which is precisely the operation that must never exist.
     *
     * @param sessionId   the session to read from
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the caller's hand
     */
    @GetMapping("/{sessionId}/hand")
    @Operation(summary = "Read your own hand")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The caller's own hand. No operation returns anybody else's."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, an unrecognised one, or one belonging to another session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "The deck has not been dealt, so there is no hand to read.")
    })
    public HandDto getOwnHand(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return HandDto.from(readOwnHandUseCase.execute(sessionId, playerToken));
    }

    /**
     * Plays one card from the caller's hand into the current trick, opening a trick
     * if none is open.
     *
     * <p>201 carries no {@code Location} header, because a single play is not
     * separately addressable: the trick is the resource, and the response body is
     * the trick as it now stands.
     *
     * @param sessionId   the session to play in
     * @param playerToken the caller's credential, absent if it sent none
     * @param request     the card to play and the optional threat annotation
     * @return 201 with the trick after the play
     */
    @PostMapping("/{sessionId}/plays")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Play a card into the current trick")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The card is played. The body is the trick as it stands, with no Location."),
        @ApiResponse(responseCode = "400", description = "The body is malformed, names no card, or breaks an annotation limit."),
        @ApiResponse(responseCode = "403", description = "No credential, or one that does not belong to this session."),
        @ApiResponse(responseCode = "404", description = "No such session, no such card, or the caller is not a member."),
        @ApiResponse(responseCode = "409", description = "Not the caller's turn, or the seat or card has already been played."),
        @ApiResponse(responseCode = "422", description = "The caller does not hold the card, or holds the led suit and played another.")
    })
    public TrickDto playCard(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken,
            @Valid @RequestBody final PlayCardRequest request) {
        final var command = new PlayCardCommand(
                sessionId,
                playerToken,
                request.cardId(),
                request.threatLinked(),
                request.components(),
                request.notes());
        return TrickDto.from(playCardUseCase.execute(command));
    }

    /**
     * Resolves the current trick and names the seat that took it.
     *
     * <p>Any seated player may call this, not only the facilitator: the outcome
     * follows entirely from cards already played, so there is nothing to decide and
     * no reason to make the table wait on one person.
     *
     * @param sessionId   the session whose current trick to resolve
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the resolved trick, now carrying a winning seat
     */
    @PostMapping("/{sessionId}/tricks/current/resolve")
    @Operation(summary = "Resolve the current trick")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The trick is resolved and winningSeat is now present."),
        @ApiResponse(responseCode = "400", description = "The session identifier is not a UUID."),
        @ApiResponse(responseCode = "403", description = "No credential, or one that does not belong to this session."),
        @ApiResponse(responseCode = "404", description = "No session exists with that identifier."),
        @ApiResponse(responseCode = "409", description = "No trick is open, it is incomplete, or it is already resolved.")
    })
    public TrickDto resolveCurrentTrick(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = SessionController.PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return TrickDto.from(resolveTrickUseCase.execute(sessionId, playerToken));
    }
}
