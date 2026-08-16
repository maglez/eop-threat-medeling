package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.GameSessionBuilder.aSession;
import static org.maglez.eop.entity.PlayerBuilder.aParticipant;
import static org.maglez.eop.entity.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.GameNotCompletedException;
import org.maglez.eop.entity.GameResult;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.PlayerBuilder;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.ScoreSheet;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionStatus;
import org.maglez.eop.entity.Standing;

/**
 * Exercises {@link GetLeaderboardUseCase}.
 *
 * <p>The leaderboard is only available once the session is COMPLETED and a result has been
 * persisted. Any other state is a conflict. Authorisation is required: a stranger who guesses
 * a session identifier must not read the result.
 *
 * <p>The use case reads tricks to compute the STRIDE breakdown. Assertions that the trick
 * repository was not asked pin the short-circuit contract (auth failures before any read).
 */
@DisplayName("GetLeaderboardUseCase")
class GetLeaderboardUseCaseTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d0");
    private static final UUID OTHER_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant FINALISED_AT = Instant.parse("2026-01-01T11:00:00Z");

    private final List<String> order = new ArrayList<>();
    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository(order, completedTable());
    private final InMemoryGameResultRepository resultRepository = new InMemoryGameResultRepository();
    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    @Nested
    @DisplayName("reading the leaderboard")
    class ReadingTheLeaderboard {

        @Test
        @DisplayName("returns the persisted game result for a completed session")
        void shouldReturnTheGameResult() {
            final var result = aGameResult();
            resultRepository.seed(result);

            final var returned = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(returned.gameResult()).isEqualTo(result);
        }

        @Test
        @DisplayName("returns a score sheet for the completed session")
        void shouldReturnAScoreSheet() {
            resultRepository.seed(aGameResult());

            final var returned = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(returned.scoreSheet()).isNotNull();
        }

        @Test
        @DisplayName("throws GameNotCompletedException when the session is not completed")
        void shouldThrowWhenSessionNotCompleted() {
            final var inProgressSession = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.IN_PROGRESS)
                    .withPlayers(seatedPlayers())
                    .build();
            final var repo = new InMemorySessionRepository(order, inProgressSession);
            final var useCase = new GetLeaderboardUseCase(
                    new ResolvePlayerUseCase(repo, java.time.Clock.systemUTC()),
                    resultRepository,
                    trickRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, tokenFor(0)));
        }

        @Test
        @DisplayName("throws GameNotCompletedException when no result has been persisted yet")
        void shouldThrowWhenNoResultPersisted() {
            // resultRepository is empty — no result seeded

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase().execute(SESSION_ID, tokenFor(0)));
        }
    }

    @Nested
    @DisplayName("authorising the caller")
    class AuthorisingTheCaller {

        @Test
        @DisplayName("refuses a token that belongs to no seat here, before reading any result")
        void shouldRefuseAStrangerBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, "a token from somewhere else"));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a missing credential, before reading any result")
        void shouldRefuseAMissingCredentialBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, null));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a session that does not exist, before reading any result")
        void shouldRefuseAnUnknownSessionBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> useCase.execute(OTHER_SESSION_ID, tokenFor(0)));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a null session identifier")
        void shouldRefuseANullSessionIdentifier() {
            final var useCase = useCase();

            assertThatNullPointerException().isThrownBy(() -> useCase.execute(null, tokenFor(0)));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses to be built without its collaborators")
        void shouldRefuseToBeBuiltWithoutCollaborators() {
            final var resolver = new ResolvePlayerUseCase(sessionRepository, java.time.Clock.systemUTC());

            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardUseCase(null, resultRepository, trickRepository));
            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardUseCase(resolver, null, trickRepository));
            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardUseCase(resolver, resultRepository, null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GetLeaderboardUseCase useCase() {
        final var resolver = new ResolvePlayerUseCase(sessionRepository, java.time.Clock.systemUTC());
        return new GetLeaderboardUseCase(resolver, resultRepository, trickRepository);
    }

    private static GameSession completedTable() {
        return aSession()
                .withSessionId(SESSION_ID)
                .withStatus(SessionStatus.COMPLETED)
                .withPlayers(seatedPlayers())
                .build();
    }

    private static List<Player> seatedPlayers() {
        return List.of(
                aPlayer().build(),
                aParticipant(1).build(),
                aParticipant(2).build());
    }

    private static String tokenFor(final int seatOrder) {
        final var base = PlayerBuilder.DEFAULT_TOKEN;
        return seatOrder == 0 ? base : base + "-" + seatOrder;
    }

    private static GameResult aGameResult() {
        final var session = completedTable();
        final var scoreSheet = ScoreSheet.of(session.players(), List.of());
        return GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);
    }

    // -------------------------------------------------------------------------
    // In-memory fake for GameResultRepository
    // -------------------------------------------------------------------------

    private static final class InMemoryGameResultRepository implements GameResultRepository {

        private GameResult stored;
        private int findBySessionIdCalls;

        void seed(final GameResult result) {
            this.stored = result;
        }

        int findBySessionIdCalls() {
            return findBySessionIdCalls;
        }

        @Override
        public void save(final GameResult result) {
            this.stored = result;
        }

        @Override
        public Optional<GameResult> findBySessionId(final UUID sessionId) {
            findBySessionIdCalls++;
            if (stored != null && stored.sessionId().equals(sessionId)) {
                return Optional.of(stored);
            }
            return Optional.empty();
        }
    }
}
