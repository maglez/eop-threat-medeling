package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.entity.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.DeckFixture;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.HandNotDealtException;
import org.maglez.eop.entity.Hands;
import org.maglez.eop.entity.NoTrickToResolveException;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.PlayerBuilder;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.Rank;
import org.maglez.eop.entity.SessionStatus;
import org.maglez.eop.entity.StrideCategory;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickAlreadyResolvedException;
import org.maglez.eop.entity.TrickNotCompleteException;
import org.maglez.eop.entity.TrickPlay;

/**
 * Exercises the resolve path of a trick.
 *
 * <p>Three assertions in here earn their keep. The first is that a refusal records no resolution:
 * every one of the four refusals below is a state a seated member can reach by asking at an ordinary
 * moment, and a use case that wrote before refusing would advance the recorded leader seat for a
 * trick that had not finished, which no later request could undo.
 *
 * <p>The second is the value sent as the next leader. While any seat still holds a card the winner
 * leads next, and once no seat does, the port has no way to say "nobody" — it takes an {@code int}
 * and rejects anything outside the seat range — so the winning seat is sent as a placeholder. Both
 * branches are pinned here, because the day a later slice learns to recognise the end of a hand it
 * needs to know which of the two it is changing.
 *
 * <p>The third is that any member may resolve, not only the facilitator. Resolution is a mechanical
 * consequence of the last card, so a test that only ever resolved as the facilitator would let a
 * facilitator-only check be added later without anything failing.
 *
 * <p>Tricks here are built by playing into them through {@link Trick#acceptPlay}, one seat at a
 * time, against the hands as they stood before that seat played. Reconstituting a finished trick
 * directly would be shorter and would skip every rule on the way in, so the fixture would no longer
 * resemble anything the application can produce.
 *
 * <p>The deck is a single suit. Following suit is then trivially satisfied and the highest rank
 * takes the trick with no trump involved, which keeps the winner obvious from the ranks dealt rather
 * than from a rule the test would have to restate.
 */
@DisplayName("ResolveTrickUseCase")
class ResolveTrickUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f0");

    private static final long HAND_PREFIX = 900L;

    private static final long PLAY_PREFIX = 910L;

    private static final int SEATS = 3;

    private static final int LEADER_SEAT = 0;

    private static final int WINNING_SEAT = 2;

    private static final int FIRST_SEQUENCE = 1;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    /**
     * Seats three players at a table already in play.
     *
     * @return a session whose status admits a trick
     */
    private static GameSession seatedTable() {
        return aSession().withPlayerCount(SEATS).withStatus(SessionStatus.IN_PROGRESS).build();
    }

    /**
     * Deals a single-suit deck of the given size, one card at a time round the table.
     *
     * @param session the session whose players are dealt to
     * @param ranks the ranks to deal, in the order they should leave the deck
     * @return the hands as dealt
     */
    private static Hands dealTo(final GameSession session, final Rank... ranks) {
        final List<Hands.Seat> seats = session.players().stream()
                .sorted(Comparator.comparingInt(Player::seatOrder))
                .map(player -> new Hands.Seat(
                        player.seatOrder(), player.playerId(), new UUID(HAND_PREFIX, player.seatOrder())))
                .toList();

        return Hands.deal(DeckFixture.cards(StrideCategory.SPOOFING, ranks), seats);
    }

    /**
     * Finds the player seated at the given seat.
     *
     * @param session the session to look in
     * @param seatOrder the seat to look for
     * @return the player seated there
     */
    private static Player playerAt(final GameSession session, final int seatOrder) {
        return session.players().stream()
                .filter(player -> player.seatOrder() == seatOrder)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Answers the plaintext token the player at the given seat presents.
     *
     * @param seatOrder the seat presenting the token
     * @return the plaintext behind that player's digest
     */
    private static String tokenForSeat(final int seatOrder) {
        return seatOrder == 0
                ? PlayerBuilder.DEFAULT_TOKEN
                : PlayerBuilder.DEFAULT_TOKEN + "-" + seatOrder;
    }

    /**
     * Plays the given seats into a trick in turn, each from the hands as they stood beforehand.
     *
     * <p>The hands are returned alongside the trick because every rule on the way in is measured
     * against the seats that still hold cards, so a caller needs the hands the trick left behind
     * rather than the ones it started from.
     *
     * @param session the session whose players are playing
     * @param dealt the hands as dealt
     * @param seatsToPlay the seats to play, in turn order
     * @return the trick and the hands after those plays
     */
    private static TrickUnderWay playInto(
            final GameSession session, final Hands dealt, final int... seatsToPlay) {
        Trick trick = Trick.open(TRICK_ID, FIRST_SEQUENCE, LEADER_SEAT);
        Hands remaining = dealt;

        for (final int seatOrder : seatsToPlay) {
            final Card card = remaining.handOf(seatOrder).cards().get(0);
            final TrickPlay candidate = new TrickPlay(
                    new UUID(PLAY_PREFIX, seatOrder),
                    playerAt(session, seatOrder).playerId(),
                    seatOrder,
                    card,
                    false,
                    List.of(),
                    null,
                    NOW);

            trick = trick.acceptPlay(seatOrder, candidate, remaining);
            remaining = remaining.withCardPlayed(seatOrder, card);
        }

        return new TrickUnderWay(trick, remaining);
    }

    /**
     * A trick and the hands it left behind.
     *
     * @param trick the trick after the plays
     * @param remaining the hands after those cards were removed
     */
    private record TrickUnderWay(Trick trick, Hands remaining) {}

    /**
     * Assembles the use case against the doubles, with a real player resolver.
     *
     * @param session the session the resolver reads
     * @return the use case under test
     */
    private ResolveTrickUseCase useCaseFor(final GameSession session) {
        return new ResolveTrickUseCase(
                new ResolvePlayerUseCase(new InMemorySessionRepository(order, session)),
                handRepository,
                trickRepository,
                FIXED);
    }

    @Test
    @DisplayName("resolves a complete trick and passes the lead to the winner")
    void shouldResolveACompleteTrickAndAdvanceToTheWinner() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(
                session, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final var resolved = useCaseFor(session)
                .execute(session.sessionId(), tokenForSeat(LEADER_SEAT));

        assertThat(order).containsExactly("recordResolution");
        assertThat(resolved.winningSeat()).isEqualTo(WINNING_SEAT);
        assertThat(trickRepository.resolutions()).hasSize(1);

        final var resolution = trickRepository.resolutions().get(0);
        assertThat(resolution.trick().winningSeat()).isEqualTo(WINNING_SEAT);
        assertThat(resolution.expectedLeaderSeat()).isEqualTo(LEADER_SEAT);
        assertThat(resolution.nextLeaderSeat()).isEqualTo(WINNING_SEAT);
        assertThat(resolution.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("sends the winning seat as the next leader once no seat holds a card")
    void shouldSendTheWinningSeatWhenNoSeatHoldsACard() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final var resolved = useCaseFor(session)
                .execute(session.sessionId(), tokenForSeat(LEADER_SEAT));

        assertThat(underWay.remaining().allEmpty()).isTrue();
        assertThat(resolved.nextLeaderSeat(underWay.remaining().seatsHoldingCards())).isEmpty();
        assertThat(trickRepository.resolutions().get(0).nextLeaderSeat())
                .isEqualTo(resolved.winningSeat());
    }

    /**
     * The other half of the next-leader rule, and the half that cannot be read off the winner.
     *
     * <p>Four cards over three seats is the unequal deal ADR-023 describes: the round robin
     * gives seat zero {@code TWO} and {@code FOUR} while seats one and two get one card each.
     * Seat one takes the trick with the ace and is immediately out of cards, so the lead cannot
     * pass to the winner and goes to the next seat clockwise that still holds one, which is seat
     * zero.
     *
     * <p>This is the only shape in which {@code nextLeaderSeat} and {@code winningSeat} disagree
     * while play continues, which makes it the only test that can tell the two apart. Without it
     * an implementation that ignored the seats holding cards and always sent the winning seat
     * would pass every other assertion in this class, and the table would then wait for a card
     * from a player who has none.
     */
    @Test
    @DisplayName("passes the lead on when the winner is out of cards but another seat is not")
    void shouldPassTheLeadOnWhenTheWinnerIsOutOfCards() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.ACE, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final var resolved = useCaseFor(session)
                .execute(session.sessionId(), tokenForSeat(LEADER_SEAT));

        assertThat(resolved.winningSeat())
                .as("the ace takes the trick, and it was dealt to seat one")
                .isEqualTo(1);
        assertThat(underWay.remaining().seatsHoldingCards())
                .as("only seat zero was dealt a second card")
                .containsExactly(LEADER_SEAT);
        assertThat(trickRepository.resolutions().get(0).nextLeaderSeat())
                .as("the winner holds nothing, so the lead passes clockwise to seat zero")
                .isEqualTo(LEADER_SEAT)
                .isNotEqualTo(resolved.winningSeat());
    }

    @Test
    @DisplayName("lets any seated member resolve, not only the facilitator")
    void shouldAllowAnyMemberToResolve() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final var resolved = useCaseFor(session).execute(session.sessionId(), tokenForSeat(1));

        assertThat(resolved.winner()).isPresent();
        assertThat(trickRepository.resolutions()).hasSize(1);
    }

    @Test
    @DisplayName("refuses a trick still waiting on a seat, and records nothing while refusing")
    void shouldRefuseAnIncompleteTrick() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());
        final ResolveTrickUseCase useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(TrickNotCompleteException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(LEADER_SEAT)))
                .satisfies(thrown -> {
                    assertThat(thrown.trickId()).isEqualTo(TRICK_ID);
                    assertThat(thrown.seatStillToPlay()).isEqualTo(1);
                });

        assertThat(trickRepository.resolutions()).isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("refuses when no trick has been led yet")
    void shouldRefuseWhenNoTrickHasBeenLed() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        handRepository.seededWith(dealt, LEADER_SEAT);
        final ResolveTrickUseCase useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(NoTrickToResolveException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(LEADER_SEAT)))
                .satisfies(thrown -> assertThat(thrown.sessionId()).isEqualTo(sessionId));

        assertThat(trickRepository.resolutions()).isEmpty();
    }

    @Test
    @DisplayName("refuses before the deal has happened")
    void shouldRefuseBeforeTheDeal() {
        final GameSession session = seatedTable();
        final ResolveTrickUseCase useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(HandNotDealtException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(LEADER_SEAT)));

        assertThat(trickRepository.resolutions()).isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("refuses a second resolution of the same trick")
    void shouldRefuseASecondResolution() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick().resolved());
        final ResolveTrickUseCase useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(TrickAlreadyResolvedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(LEADER_SEAT)))
                .satisfies(thrown -> assertThat(thrown.trickId()).isEqualTo(TRICK_ID));

        assertThat(trickRepository.resolutions()).isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("refuses a stranger before reading a single hand")
    void shouldRefuseAStranger() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, 0, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());
        final ResolveTrickUseCase useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, "not-a-seated-player"));

        assertThat(handRepository.sessionsAsked()).isEmpty();
        assertThat(trickRepository.resolutions()).isEmpty();
    }
}
