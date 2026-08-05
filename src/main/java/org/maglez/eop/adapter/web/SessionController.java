package org.maglez.eop.adapter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.DisplayName;
import org.maglez.eop.usecase.CreateSessionUseCase;
import org.maglez.eop.usecase.GetSessionStateUseCase;
import org.maglez.eop.usecase.JoinSessionUseCase;
import org.maglez.eop.usecase.StartSessionUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to the session lifecycle: create a lobby, join one, read its state,
 * close it and begin play.
 *
 * <p>This bean only exists when {@code eop.features.session-lifecycle} is true
 * (ADR-013, ADR-019). That is the whole of the feature flag: while the flag is off
 * there are no handlers registered for these paths, so Spring's own no-handler
 * response answers them, and that response is already a problem detail. Nothing
 * branches on the flag at request time, because behaviour that is switched off by
 * the absence of code cannot be switched on by accident.
 *
 * <p>The credential header is declared {@code required = false} on purpose. Letting
 * Spring reject a missing header would produce a 400 describing a missing header,
 * and the absence of a credential is not a malformed request — it is a refused one.
 * Passing the null through to the use case makes a missing credential and an
 * unrecognised one arrive at the same place and leave as the same 403.
 *
 * <p>Descriptions here are deliberately brief: {@code docs/api/openapi.yml} is the
 * contract (ADR-004), springdoc is disabled in production, and duplicating prose in
 * two places is how the two come to disagree.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(prefix = "eop.features", name = "session-lifecycle")
@Tag(name = "sessions", description = "Game session lifecycle")
public class SessionController {

    /**
     * The header carrying a player's identity credential.
     *
     * <p>Custom rather than {@code Authorization: Bearer}, because this is not
     * OAuth and naming it so would invite the assumption that it behaves like one
     * (ADR-015). It is never accepted as a query parameter, on any endpoint,
     * including the event stream (ADR-019).
     */
    static final String PLAYER_TOKEN_HEADER = "X-EoP-Player-Token";

    private final CreateSessionUseCase createSessionUseCase;

    private final JoinSessionUseCase joinSessionUseCase;

    private final GetSessionStateUseCase getSessionStateUseCase;

    private final StartSessionUseCase startSessionUseCase;

    SessionController(
            final CreateSessionUseCase createSessionUseCase,
            final JoinSessionUseCase joinSessionUseCase,
            final GetSessionStateUseCase getSessionStateUseCase,
            final StartSessionUseCase startSessionUseCase) {
        this.createSessionUseCase = Objects.requireNonNull(createSessionUseCase, "createSessionUseCase is required");
        this.joinSessionUseCase = Objects.requireNonNull(joinSessionUseCase, "joinSessionUseCase is required");
        this.getSessionStateUseCase = Objects.requireNonNull(getSessionStateUseCase, "getSessionStateUseCase is required");
        this.startSessionUseCase = Objects.requireNonNull(startSessionUseCase, "startSessionUseCase is required");
    }

    /**
     * Opens a lobby and seats its facilitator.
     *
     * @param request the display name to seat the facilitator under
     * @return 201 with the new session, the facilitator's identifier and its credential
     */
    @PostMapping
    @Operation(summary = "Create a session and become its facilitator")
    public ResponseEntity<SessionAdmissionDto> createSession(@Valid @RequestBody final CreateSessionRequest request) {
        final var admission = createSessionUseCase.execute(DisplayName.of(request.displayName()));
        final var location = URI.create("/api/v1/sessions/" + admission.session().sessionId());
        return ResponseEntity.created(location).body(SessionAdmissionDto.from(admission));
    }

    /**
     * Takes the next seat at a table identified by its join code.
     *
     * @param joinCode    the code as typed, normalised and matched further in
     * @param request     the display name to seat the player under
     * @param httpRequest the servlet request, read only for the caller's address
     * @return 200 with the joined session, the player's identifier and its credential
     */
    @PostMapping("/{joinCode}/players")
    @Operation(summary = "Join a session using its code")
    public SessionAdmissionDto joinSession(
            @PathVariable final String joinCode,
            @Valid @RequestBody final JoinSessionRequest request,
            final HttpServletRequest httpRequest) {
        final var admission = joinSessionUseCase.execute(
                joinCode, DisplayName.of(request.displayName()), ClientAddresses.of(httpRequest));
        return SessionAdmissionDto.from(admission);
    }

    /**
     * Reads a session as it is stored.
     *
     * <p>This is the reconnect endpoint and the first-load endpoint at once. It
     * consults the database and nothing else, so a client returning after a refresh,
     * a dropped connection or a deployment follows the same path as one arriving for
     * the first time (ADR-014).
     *
     * @param sessionId   the session to read
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the current state
     */
    @GetMapping("/{sessionId}")
    @Operation(summary = "Read the current state of a session")
    public SessionStateDto getSessionState(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return SessionStateDto.from(getSessionStateUseCase.execute(sessionId, playerToken));
    }

    /**
     * Closes the lobby and begins play.
     *
     * <p>Dealing is not part of this: starting establishes that no more players
     * will arrive, and nothing more.
     *
     * @param sessionId   the session to start
     * @param playerToken the caller's credential, absent if it sent none
     * @return 200 with the started session
     */
    @PostMapping("/{sessionId}/start")
    @Operation(summary = "Start play, closing the lobby")
    public SessionStateDto startSession(
            @PathVariable final UUID sessionId,
            @RequestHeader(name = PLAYER_TOKEN_HEADER, required = false) final String playerToken) {
        return SessionStateDto.from(startSessionUseCase.execute(sessionId, playerToken));
    }
}
