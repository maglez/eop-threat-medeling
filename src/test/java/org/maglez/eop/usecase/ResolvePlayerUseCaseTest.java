package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.GameSessionBuilder.aSession;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.PlayerBuilder;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.PlayerRole;
import org.maglez.eop.entity.SessionNotFoundException;

/**
 * Behaviour of the use case that turns a credential back into a player.
 *
 * <p>Every endpoint that acts on behalf of a player passes through here, so the
 * important assertions are about what the refusals give away. A credential that is
 * absent, blank, or simply wrong all produce the same exception with the same
 * message: a caller holding a session identifier must not be able to tell whether a
 * guessed credential belongs to nobody or to somebody else.
 */
@DisplayName("ResolvePlayerUseCase")
class ResolvePlayerUseCaseTest {

    private static final String FACILITATOR_TOKEN = PlayerBuilder.DEFAULT_TOKEN;
    private static final UUID ABSENT_SESSION = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    @Test
    @DisplayName("returns the session together with the player holding the credential")
    void shouldResolveTheHolderOfTheCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository(session));

        final var resolved = useCase.execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(resolved.session().sessionId()).isEqualTo(session.sessionId());
        assertThat(resolved.player().seatOrder()).isZero();
        assertThat(resolved.player().role()).isEqualTo(PlayerRole.FACILITATOR);
    }

    @Test
    @DisplayName("resolves a participant rather than always answering with the facilitator")
    void shouldResolveAParticipant() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository(session));

        final var resolved = useCase.execute(session.sessionId(), FACILITATOR_TOKEN + "-2");

        assertThat(resolved.player().seatOrder()).isEqualTo(2);
        assertThat(resolved.player().role()).isEqualTo(PlayerRole.PARTICIPANT);
    }

    @Test
    @DisplayName("reports a session that does not exist as missing, not as a bad credential")
    void shouldReportAnAbsentSessionAsMissing() {
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository());

        assertThatExceptionOfType(SessionNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ABSENT_SESSION, FACILITATOR_TOKEN))
                .withMessageContaining(ABSENT_SESSION.toString());
    }

    @Test
    @DisplayName("rejects a missing session identifier outright")
    void shouldRejectANullSessionIdentifier() {
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository());

        assertThatNullPointerException()
                .isThrownBy(() -> useCase.execute(null, FACILITATOR_TOKEN))
                .withMessageContaining("sessionId");
    }

    @Test
    @DisplayName("answers an absent, a blank and a wrong credential identically")
    void shouldNotDistinguishBetweenWaysOfFailing() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository(session));

        final var absent = refusalFor(useCase, session.sessionId(), null);
        final var blank = refusalFor(useCase, session.sessionId(), "   ");
        final var wrong = refusalFor(useCase, session.sessionId(), "not-anybodys-credential");

        assertThat(blank).hasMessage(absent.getMessage());
        assertThat(wrong).hasMessage(absent.getMessage());
        assertThat(absent).hasMessageContaining(session.sessionId().toString());
    }

    @Test
    @DisplayName("never echoes the rejected credential back in the message")
    void shouldNotEchoTheRejectedCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerUseCase(new InMemorySessionRepository(session));

        final var refusal = refusalFor(useCase, session.sessionId(), "guessed-credential");

        assertThat(refusal.getMessage()).doesNotContain("guessed-credential");
    }

    private PlayerNotRecognisedException refusalFor(
            final ResolvePlayerUseCase useCase, final UUID sessionId, final String token) {
        try {
            useCase.execute(sessionId, token);
        } catch (final PlayerNotRecognisedException refused) {
            return refused;
        }
        throw new AssertionError("the credential should have been refused");
    }
}
