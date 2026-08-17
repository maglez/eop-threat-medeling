package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.DeckFixture;
import org.maglez.eop.entity.GameNotCompletedException;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.PlayerBuilder;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionStatus;

/**
 * Tests {@link NewGameUseCase}.
 *
 * <p>The interesting claims here are about authorisation and sequencing. Authorisation is
 * two-layered: the caller must be the facilitator, and the session must be completed. Sequencing
 * matters because the clear operations must run before the reset, and the deal must follow the
 * reset — a deal against a COMPLETED session would be refused by the real adapter.
 *
 * <p>The shared call log threads through all four collaborators so a single assertion can pin
 * the order across port boundaries. Refusal tests assert the call count as well as the exception,
 * because a use case that throws after writing has still written.
 */
@DisplayName("NewGameUseCase")
class NewGameUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID FIRST_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e0");

    private static final UUID SECOND_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    private static final UUID THIRD_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e2");

    private static final int SEATS = 3;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    private final RecordingDeckShuffler shuffler = new RecordingDeckShuffler(order);

    private final InMemoryCardRepository cardRepository =
            new InMemoryCardRepository(DeckFixture.fullDeck().toArray(new Card[0]));

    private final QueuedIdentifierGenerator identifiers =
            new QueuedIdentifierGenerator(FIRST_HAND, SECOND_HAND, THIRD_HAND);

    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("clears tricks, then hands, then resets session, then deals")
        void shouldExecuteInOrder() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(order).containsSubsequence(
                    "clearTricksForNewGame",
                    "clearHandsForNewGame",
                    "resetToInProgress",
                    "shuffle",
                    "recordDeal",
                    "publish");
        }

        @Test
        @DisplayName("deals the whole deck to the same seats")
        void shouldDealToSameSeats() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(handRepository.recordDealCalls()).isOne();
            assertThat(handRepository.recordedHands().totalCards())
                    .isEqualTo(DeckFixture.fullDeck().size());
            assertThat(handRepository.recordedHands().seats())
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("mints exactly one hand identifier per seat")
        void shouldMintOneHandIdentifierPerSeat() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(identifiers.issued()).isEqualTo(SEATS);
        }

        @Test
        @DisplayName("publishes HAND_DEALT after the deal is written")
        void shouldPublishHandDealtAfterWrite() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(publisher.published()).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo(SessionEventType.HAND_DEALT);
                assertThat(event.sessionId()).isEqualTo(session.sessionId());
                assertThat(event.occurredAt()).isEqualTo(NOW);
            });
            assertThat(order).containsSubsequence("recordDeal", "publish");
        }
    }

    @Nested
    @DisplayName("Authorisation")
    class Authorisation {

        @Test
        @DisplayName("refuses a participant, and writes nothing while refusing")
        void shouldRefuseAParticipant() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);
            final var participant = tokenForSeat(1);

            assertThatExceptionOfType(NotFacilitatorException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), participant));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
            assertThat(order).doesNotContain("clearTricksForNewGame", "clearHandsForNewGame",
                    "resetToInProgress", "recordDeal", "publish");
        }

        @Test
        @DisplayName("refuses a stranger, and writes nothing while refusing")
        void shouldRefuseAStranger() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), "not-a-seated-player"));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
        }

        @Test
        @DisplayName("refuses a session that is still in progress, not completed")
        void shouldRefuseAnInProgressSession() {
            final var session = aSession().withPlayerCount(SEATS).withStatus(SessionStatus.IN_PROGRESS).build();
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN))
                    .satisfies(ex -> assertThat(ex.sessionId()).isEqualTo(session.sessionId()));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
            assertThat(order).doesNotContain("clearTricksForNewGame", "clearHandsForNewGame",
                    "resetToInProgress", "recordDeal");
        }

        @Test
        @DisplayName("refuses a lobby session, not completed")
        void shouldRefuseALobbySession() {
            final var session = aSession().withPlayerCount(SEATS).withStatus(SessionStatus.LOBBY).build();
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN));

            assertThat(handRepository.recordDealCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("rejects a null session identifier")
        void shouldRejectNullSessionId() {
            final var sessionRepository = new InMemorySessionRepository(order);
            final var useCase = useCaseFor(sessionRepository);

            assertThatNullPointerException()
                    .isThrownBy(() -> useCase.execute(null, PlayerBuilder.DEFAULT_TOKEN));
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() {
            final var sessionRepository = new InMemorySessionRepository(order);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(org.maglez.eop.entity.SessionNotFoundException.class)
                    .isThrownBy(() -> useCase.execute(UUID.randomUUID(), PlayerBuilder.DEFAULT_TOKEN));
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null resolvePlayerUseCase")
        void shouldRejectNullResolvePlayerUseCase() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameUseCase(
                    null, handRepository, trickRepository, sessionRepository,
                    cardRepository, shuffler, identifiers, publisher, FIXED));
        }

        @Test
        @DisplayName("rejects a null handRepository")
        void shouldRejectNullHandRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameUseCase(
                    new ResolvePlayerUseCase(sessionRepository, Clock.systemUTC()),
                    null, trickRepository, sessionRepository,
                    cardRepository, shuffler, identifiers, publisher, FIXED));
        }

        @Test
        @DisplayName("rejects a null trickRepository")
        void shouldRejectNullTrickRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameUseCase(
                    new ResolvePlayerUseCase(sessionRepository, Clock.systemUTC()),
                    handRepository, null, sessionRepository,
                    cardRepository, shuffler, identifiers, publisher, FIXED));
        }

        @Test
        @DisplayName("rejects a null sessionRepository")
        void shouldRejectNullSessionRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameUseCase(
                    new ResolvePlayerUseCase(sessionRepository, Clock.systemUTC()),
                    handRepository, trickRepository, null,
                    cardRepository, shuffler, identifiers, publisher, FIXED));
        }

        @Test
        @DisplayName("rejects a null clock")
        void shouldRejectNullClock() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameUseCase(
                    new ResolvePlayerUseCase(sessionRepository, Clock.systemUTC()),
                    handRepository, trickRepository, sessionRepository,
                    cardRepository, shuffler, identifiers, publisher, null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static GameSession completedTable(final int players) {
        return aSession().withPlayerCount(players).withStatus(SessionStatus.COMPLETED).build();
    }

    private static String tokenForSeat(final int seat) {
        return PlayerBuilder.DEFAULT_TOKEN + "-" + seat;
    }

    private NewGameUseCase useCaseFor(final InMemorySessionRepository sessionRepository) {
        return new NewGameUseCase(
                new ResolvePlayerUseCase(sessionRepository, Clock.systemUTC()),
                handRepository,
                trickRepository,
                sessionRepository,
                cardRepository,
                shuffler,
                identifiers,
                publisher,
                FIXED);
    }
}
