package org.maglez.eop.entity;

import java.util.UUID;

/**
 * Raised when a session is requested by an identifier no session has.
 *
 * <p>Deliberately candid, unlike {@link UnknownJoinCodeException}. A session
 * identifier is an unguessable UUID, so there is nothing to be gained by hiding
 * whether one exists — a caller who can name the identifier either had it or
 * guessed 122 bits. A join code is six characters, so that lookup hides it.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. Mapping it to an
 * HTTP status is the interface layer's job; the domain does not know HTTP exists.
 */
public class SessionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a specific unknown identifier.
     *
     * @param sessionId the identifier that matched no session
     */
    public SessionNotFoundException(final UUID sessionId) {
        super("No session found with identifier " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The identifier that matched no session.
     *
     * @return the requested identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
