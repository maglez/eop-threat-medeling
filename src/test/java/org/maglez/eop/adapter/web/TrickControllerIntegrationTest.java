package org.maglez.eop.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Exercises the five trick-play routes end to end, through the real use cases, the real adapter and the real database.
 *
 * <p>Nothing is stubbed. A test that mocked the repository would assert that the controller calls the code the
 * controller calls, which is a tautology: the interesting claims of this slice all live in the seams. That the acting
 * seat comes from the identity token and not from the request body is a claim about what the controller passes on. That
 * a collision between two simultaneous plays becomes an RFC 9457 refusal rather than a stack trace is a claim about the
 * unique constraint, the adapter's translation of it and the exception handler, in that order. Neither survives being
 * mocked out.</p>
 *
 * <p>Every test seats its own table, because a session is cheap and shared fixtures across tests in a suite that also
 * runs concurrent requests would couple the tests to each other's timing.</p>
 *
 * <p>The tables here hold three players, the fewest that can start play. The deck is 78 cards, so three players is also
 * the one table size where the deal comes out even at 26 each: an uneven deal is exercised where the arithmetic lives,
 * in the domain tests for {@code Hands}, and repeating it here would test the same code through a slower path.</p>
 *
 * <p>Several tests need to know which seat leads. They work it out by reading every player's hand, which they can do
 * only because a test holds every token. Since EOP-14 Slice E a real client can simply read the state of play, which
 * publishes the seat to play; the fixture keeps deriving the answer from the cards because a test that asked the server
 * whose turn it is and then asserted the server's answer would assert nothing. The awkwardness of the fixture is what
 * makes the assertion independent.</p>
 *
 * <p>The state-of-play route is asserted against the same two authorities it publishes: the seat the session row records
 * as leading and the seat the cards say may play. Those are separately derived on the way out and nothing reconciles
 * them, so a test that read only one of them would pass while the other drifted.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Trick play endpoints")
class TrickControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String TAMPERING = "TAMPERING";

    private static final String TRUMP = "ELEVATION_OF_PRIVILEGE";

    private static final int PLAYERS = 3;

    private static final int CARDS_EACH = 26;

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Dealing the deck")
    class Dealing {

        @Test
        @DisplayName("gives the whole deck away and answers with no body")
        void shouldDealToEverySeat() throws Exception {
            final var table = startedTable();

            final var dealt = deal(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(dealt.getResponse().getStatus())
                    .as("the facilitator deals, and there is nothing to say back")
                    .isEqualTo(204);
            Assertions.assertThat(dealt.getResponse().getContentAsString())
                    .as("a deal response would have to name every hand or privilege one seat, so it names none")
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses a participant, because dealing is the facilitator's job")
        void shouldRefuseAParticipant() throws Exception {
            final var table = startedTable();

            final var refused = deal(table.sessionId(), table.seats().get(1).playerToken());

            // The title names the rule, not the operation: one exception serves every
            // facilitator-only action, so it says "start play" even when the refused
            // action was dealing. Asserted as it is rather than as it reads best,
            // because the alternative is a test that passes against a message no
            // client will see.
            assertProblem(refused, 403, "Only the facilitator can start play");
        }

        @Test
        @DisplayName("refuses a second deal, because the first one is the hand being played")
        void shouldRefuseASecondDeal() throws Exception {
            final var table = dealtTable();

            final var again = deal(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(again.getResponse().getStatus())
                    .as("the session already holds a hand, which is a state problem and not a bad request")
                    .isEqualTo(409);
            assertProblemJson(again);
        }

        @Test
        @DisplayName("refuses to deal into a lobby, because play has not started")
        void shouldRefuseBeforePlayStarts() throws Exception {
            final var table = seatedTable();

            final var tooEarly = deal(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(tooEarly.getResponse().getStatus())
                    .as("the session is not playable yet, and the store is what refuses it")
                    .isEqualTo(409);
            assertProblemJson(tooEarly);
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAMissingToken() throws Exception {
            final var table = startedTable();

            final var anonymous = deal(table.sessionId(), null);

            assertProblem(anonymous, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent, not as forbidden")
        void shouldReportAnUnknownSession() throws Exception {
            final var table = startedTable();

            final var elsewhere = deal(UUID.randomUUID().toString(), table.facilitator().playerToken());

            assertProblem(elsewhere, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = startedTable();

            final var refused = deal("not-a-uuid", table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }
    }

    @Nested
    @DisplayName("Reading your own hand")
    class ReadingOwnHand {

        @Test
        @DisplayName("gives each player their own cards and nobody else's")
        void shouldGiveEachPlayerTheirOwnCards() throws Exception {
            final var table = dealtTable();
            final var seen = new ArrayList<String>();

            for (final var player : table.seats()) {
                final var cards = handOf(table.sessionId(), player);

                Assertions.assertThat(cards)
                        .as("three players share 78 cards evenly")
                        .hasSize(CARDS_EACH);
                seen.addAll(cards.stream().map(CardView::cardId).toList());
            }

            Assertions.assertThat(seen)
                    .as("no card is in two hands at once, so no player can see another player's cards")
                    .doesNotHaveDuplicates()
                    .hasSize(PLAYERS * CARDS_EACH);
        }

        @Test
        @DisplayName("counts the cards it returns")
        void shouldCountTheCardsItReturns() throws Exception {
            final var table = dealtTable();

            final var body = readHand(table.sessionId(), table.facilitator().playerToken()).getResponse()
                    .getContentAsString();

            final var document = JsonPath.parse(body);
            Assertions.assertThat((Integer) document.read("$.cardCount"))
                    .as("the count is taken from the cards, so the two cannot disagree")
                    .isEqualTo(CARDS_EACH);
            Assertions.assertThat(document.read("$.playerId", String.class))
                    .as("the hand names its owner, which is the caller")
                    .isEqualTo(table.facilitator().playerId());
        }

        @Test
        @DisplayName("refuses before the deal, because there is no hand yet")
        void shouldRefuseBeforeTheDeal() throws Exception {
            final var table = startedTable();

            final var tooEarly = readHand(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(tooEarly.getResponse().getStatus())
                    .as("an undealt session is a state problem, not a missing resource")
                    .isEqualTo(409);
            assertProblemJson(tooEarly);
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAMissingToken() throws Exception {
            final var table = dealtTable();

            final var anonymous = readHand(table.sessionId(), null);

            assertProblem(anonymous, 403, "Player not recognised");
        }

        @Test
        @DisplayName("refuses a token minted for another session")
        void shouldRefuseATokenFromAnotherSession() throws Exception {
            final var mine = dealtTable();
            final var theirs = dealtTable();

            final var trespass = readHand(mine.sessionId(), theirs.facilitator().playerToken());

            assertProblem(trespass, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session")
        void shouldReportAnUnknownSession() throws Exception {
            final var table = dealtTable();

            final var nowhere = readHand(UUID.randomUUID().toString(), table.facilitator().playerToken());

            assertProblem(nowhere, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = dealtTable();

            final var refused = readHand("not-a-uuid", table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("does not repeat the seat the caller already knows")
        void shouldNotRepeatTheSeat() throws Exception {
            final var table = dealtTable();

            final var own = readHand(table.sessionId(), table.facilitator().playerToken());

            // The caller finds its own seat in the session state by matching the player id it was
            // given at admission. Repeating it here would be a second copy of game data that could
            // disagree with the first, so its absence is asserted rather than left to the schema.
            Assertions.assertThat(own.getResponse().getContentAsString())
                    .as("the hand names no seat")
                    .doesNotContain("seatOrder");
        }
    }

    @Nested
    @DisplayName("Playing a card")
    class PlayingACard {

        @Test
        @DisplayName("refuses every seat but the one that leads, and refuses it before anything is written")
        void shouldAcceptOnlyTheSeatThatLeads() throws Exception {
            final var table = dealtTable();
            final var leader = leaderSeatOf(table);

            // Every non-leading seat is probed while the trick is still unopened. Probing
            // them one after another *while* the trick fills up would prove nothing: once
            // the leader has played, the next seat clockwise is legitimately on turn, so a
            // 201 there is correct behaviour rather than a hole. The refusal only means
            // something before the lead.
            for (var seat = 0; seat < PLAYERS; seat++) {
                if (seat == leader) {
                    continue;
                }
                final var player = table.seats().get(seat);
                final var early = playCard(table.sessionId(), player.playerToken(),
                        playRequest(handOf(table.sessionId(), player).get(0).cardId()));

                assertProblem(early, 409, "Not your turn");
                Assertions.assertThat(handOf(table.sessionId(), player))
                        .as("a refused play writes nothing, so the card is still in the hand that offered it")
                        .hasSize(CARDS_EACH);
            }

            final var onTurn = table.seats().get(leader);
            final var accepted = playCard(table.sessionId(), onTurn.playerToken(),
                    playRequest(handOf(table.sessionId(), onTurn).get(0).cardId()));

            Assertions.assertThat(accepted.getResponse().getStatus())
                    .as("the opening lead belongs to the seat holding the lowest Tampering card")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("takes the acting seat from the credential and ignores a body that claims one")
        void shouldTakeTheSeatFromTheCredential() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var impostor = table.seats().get((leaderSeatOf(table) + 1) % PLAYERS);
            final var card = handOf(table.sessionId(), leader).get(0);

            final var body = """
                    {"cardId":"%s","seatOrder":%d,"playerId":"%s","suit":"ELEVATION_OF_PRIVILEGE","rank":"ACE"}"""
                    .formatted(card.cardId(), impostor.seatOrder(), impostor.playerId());
            final var played = playCard(table.sessionId(), leader.playerToken(), body);

            // The forged fields are read by nothing, so the request is an ordinary legal
            // play. What earns this test its keep is the four assertions below: they are
            // the evidence that the seat, the player, the suit and the rank all come from
            // somewhere the caller cannot reach. Refusing unknown fields outright would be
            // stronger still, but that setting is global to every endpoint and belongs to a
            // decision of its own.
            Assertions.assertThat(played.getResponse().getStatus())
                    .as("an unread field changes nothing, so the play is accepted on its merits")
                    .isEqualTo(201);
            final var document = JsonPath.parse(played.getResponse().getContentAsString());
            Assertions.assertThat((Integer) document.read("$.plays[0].seatOrder"))
                    .as("the seat is the token's seat: a body claiming another seat cannot play out of that hand")
                    .isEqualTo(leader.seatOrder());
            Assertions.assertThat(document.read("$.plays[0].playerId", String.class))
                    .as("the player is the token's player, whatever the body says")
                    .isEqualTo(leader.playerId());
            Assertions.assertThat(document.read("$.plays[0].card.suit", String.class))
                    .as("the suit comes from the deck row named by cardId, so a forged suit cannot win a trick")
                    .isEqualTo(card.suit());
            Assertions.assertThat((Integer) document.read("$.plays[0].card.rankValue"))
                    .as("the rank likewise comes from the deck, not from the caller")
                    .isEqualTo(card.rankValue());
        }

        @Test
        @DisplayName("opens the trick it answers with")
        void shouldAnswerWithTheOpenedTrick() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var played = playCard(table.sessionId(), leader.playerToken(), playRequest(card.cardId()));

            Assertions.assertThat(played.getResponse().getStatus()).isEqualTo(201);
            Assertions.assertThat(played.getResponse().getHeader("Location"))
                    .as("a single play is not separately addressable, so there is nowhere to point")
                    .isNull();
            final var document = JsonPath.parse(played.getResponse().getContentAsString());
            Assertions.assertThat((Integer) document.read("$.sequence"))
                    .as("the first trick of the hand")
                    .isEqualTo(1);
            Assertions.assertThat((Integer) document.read("$.leaderSeat")).isEqualTo(leader.seatOrder());
            Assertions.assertThat(document.read("$.ledSuit", String.class))
                    .as("the led suit is the suit of the first card, and it is settled by playing it")
                    .isEqualTo(card.suit());
            Assertions.assertThat(document.read("$.plays", List.class)).hasSize(1);
            Assertions.assertThat(played.getResponse().getContentAsString())
                    .as("an unresolved trick has no winning seat, and its absence is how a client tells")
                    .doesNotContain("winningSeat");
        }

        @Test
        @DisplayName("takes the card out of the hand that played it")
        void shouldRemoveThePlayedCardFromTheHand() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            playCard(table.sessionId(), leader.playerToken(), playRequest(card.cardId()));

            Assertions.assertThat(handOf(table.sessionId(), leader))
                    .as("a played card has left the hand, so it cannot be played twice")
                    .hasSize(CARDS_EACH - 1)
                    .noneMatch(remaining -> remaining.cardId().equals(card.cardId()));
        }

        @Test
        @DisplayName("refuses a card the caller does not hold")
        void shouldRefuseACardTheCallerDoesNotHold() throws Exception {
            final var table = dealtTable();
            final var leaderSeat = leaderSeatOf(table);
            final var leader = table.seats().get(leaderSeat);
            final var somebodyElse = table.seats().get((leaderSeat + 1) % PLAYERS);
            final var notMine = handOf(table.sessionId(), somebodyElse).get(0);

            final var refused = playCard(table.sessionId(), leader.playerToken(), playRequest(notMine.cardId()));

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("no amount of waiting makes a card the caller never held playable")
                    .isEqualTo(422);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a card that is not in the deck at all")
        void shouldRefuseAnUnknownCard() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));

            final var refused = playCard(table.sessionId(), leader.playerToken(),
                    playRequest(UUID.randomUUID().toString()));

            assertProblem(refused, 404, "Card not found");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAMissingToken() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var anonymous = playCard(table.sessionId(), null, playRequest(card.cardId()));

            assertProblem(anonymous, 403, "Player not recognised");
        }

        @Test
        @DisplayName("refuses a body with no card at all")
        void shouldRefuseAMissingCard() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));

            final var refused = playCard(table.sessionId(), leader.playerToken(), "{}");

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("the card is the one thing the body must carry")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a card of another suit while the caller holds the led suit")
        void shouldRefuseAPlayThatDoesNotFollowSuit() throws Exception {
            final var table = dealtTable();
            final var leaderSeat = leaderSeatOf(table);
            final var leader = table.seats().get(leaderSeat);
            final var follower = table.seats().get((leaderSeat + 1) % PLAYERS);

            // The refusal only means anything if the follower could have followed suit, so the suit
            // is chosen from the two hands rather than assumed. An earlier version led an arbitrary
            // card and asserted the follower happened to hold that suit, on the stated grounds that
            // every hand holds all six suits at this table size. That is very likely and not true:
            // 78 cards over three seats leave a hand missing a named suit about three times in a
            // thousand, which is rare enough to read as certain and often enough to turn a build
            // red for a reason that is nothing to do with following suit.
            final var hand = handOf(table.sessionId(), follower);
            final var led = handOf(table.sessionId(), leader).stream()
                    .filter(card -> hand.stream().anyMatch(held -> held.suit().equals(card.suit())))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the leader and the follower share no suit"));
            playCard(table.sessionId(), leader.playerToken(), playRequest(led.cardId()));

            final var offSuit = hand.stream()
                    .filter(card -> !card.suit().equals(led.suit()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the follower holds nothing but the led suit"));

            final var refused = playCard(table.sessionId(), follower.playerToken(), playRequest(offSuit.cardId()));

            assertProblem(refused, 422, "You must follow suit");
        }

        @Test
        @DisplayName("refuses a body that is not JSON")
        void shouldRefuseAMalformedBody() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));

            final var refused = playCard(table.sessionId(), leader.playerToken(), "{\"cardId\":");

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("a body the parser cannot read is the caller's mistake, not a server fault")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));

            final var refused = playCard("not-a-uuid", leader.playerToken(),
                    playRequest(UUID.randomUUID().toString()));

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }
    }

    @Nested
    @DisplayName("Annotating a play")
    class Annotating {

        @Test
        @DisplayName("accepts a play with no annotation at all")
        void shouldAcceptAnUnannotatedPlay() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var played = playCard(table.sessionId(), leader.playerToken(),
                    "{\"cardId\":\"%s\"}".formatted(card.cardId()));

            Assertions.assertThat(played.getResponse().getStatus())
                    .as("an unlinked play is an ordinary outcome, not a degraded one")
                    .isEqualTo(201);
            final var body = played.getResponse().getContentAsString();
            Assertions.assertThat(JsonPath.parse(body).read("$.plays[0].threatLinked", Boolean.class))
                    .as("an omitted flag defaults to false rather than failing validation")
                    .isFalse();
            Assertions.assertThat(body)
                    .as("an absent note is an absent field, not a null one")
                    .doesNotContain("notes");
        }

        @Test
        @DisplayName("accepts a play that says outright it is not threat linked")
        void shouldAcceptAnExplicitlyUnlinkedPlay() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var played = playCard(table.sessionId(), leader.playerToken(),
                    "{\"cardId\":\"%s\",\"threatLinked\":false}".formatted(card.cardId()));

            // Sending false and omitting the field are different requests that must reach the
            // same outcome. The flag is boxed so an absent value can be told from a false one
            // inside the request record, and this is the arm that proves the two then converge.
            Assertions.assertThat(played.getResponse().getStatus())
                    .as("saying no threat was linked is as ordinary as not saying anything")
                    .isEqualTo(201);
            Assertions.assertThat(JsonPath.parse(played.getResponse().getContentAsString())
                            .read("$.plays[0].threatLinked", Boolean.class))
                    .as("an explicit false survives the boundary as false")
                    .isFalse();
        }

        @Test
        @DisplayName("refuses a null inside the component list rather than passing it inward")
        void shouldRefuseANullComponent() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var refused = playCard(table.sessionId(), leader.playerToken(),
                    "{\"cardId\":\"%s\",\"components\":[null]}".formatted(card.cardId()));

            // The one shape a size constraint waves through: @Size holds for a null element,
            // so without the copy in the request record this would travel inward and be
            // rejected somewhere far from the caller who sent it.
            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("a nameless component is refused at the boundary, not deeper in")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a note carrying a line break")
        void shouldRefuseANoteWithALineBreak() throws Exception {
            final var refused = playWithBody("{\"cardId\":\"%s\",\"notes\":\"first line\\nsecond line\"}");

            // A carriage return or newline in stored free text is the whole mechanism of log
            // forging (CWE-117): the day anything logs a play, a note holding a line break
            // would let its author write a log line of their own. The domain refuses control
            // characters when it builds the play, and nothing here logs yet, so this test is
            // the only thing pinning the status a caller sees for it. Without it, a change to
            // the handler that maps this refusal could turn a 400 into a 500 unnoticed.
            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("a control character in free text is a client error, not a server one")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a component name carrying a control character")
        void shouldRefuseAComponentNameWithAControlCharacter() throws Exception {
            final var refused = playWithBody("{\"cardId\":\"%s\",\"components\":[\"Payments\\u0000API\"]}");

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("component names are held to the same rule as notes")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        /**
         * Deals a table and plays the leader's first card with the given body template, into
         * which the card's identifier is interpolated.
         *
         * @param bodyTemplate a JSON body with one {@code %s} placeholder for the card identifier
         * @return the result of the play, for the caller to assert on
         * @throws Exception if the request cannot be performed
         */
        private MvcResult playWithBody(final String bodyTemplate) throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            return playCard(table.sessionId(), leader.playerToken(), bodyTemplate.formatted(card.cardId()));
        }

        @Test
        @DisplayName("accepts an annotation exactly at the boundary")
        void shouldAcceptTheBoundary() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            final var played = playCard(table.sessionId(), leader.playerToken(),
                    annotatedRequest(card.cardId(), 20, 200, 2000));

            Assertions.assertThat(played.getResponse().getStatus())
                    .as("the limits are inclusive, so the largest permitted annotation is permitted")
                    .isEqualTo(201);
            Assertions.assertThat(JsonPath.parse(played.getResponse().getContentAsString())
                    .read("$.plays[0].components", List.class))
                    .hasSize(20);
        }

        @Test
        @DisplayName("refuses a twenty-first component")
        void shouldRefuseTooManyComponents() throws Exception {
            final var refused = playAnnotated(21, 200, 2000);

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("the request is malformed against the published limit, which is a bad request")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses an over-long component name")
        void shouldRefuseAnOverLongComponentName() throws Exception {
            final var refused = playAnnotated(1, 201, 2000);

            Assertions.assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses an over-long note")
        void shouldRefuseAnOverLongNote() throws Exception {
            final var refused = playAnnotated(1, 200, 2001);

            Assertions.assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertProblemJson(refused);
        }

        /**
         * Plays one legal opening lead carrying an annotation of the given shape.
         *
         * @param components      how many component names to send
         * @param componentLength the length of each component name
         * @param noteLength      the length of the note
         * @return the result of the play, whatever its status
         * @throws Exception if the fixture requests fail
         */
        private MvcResult playAnnotated(final int components, final int componentLength, final int noteLength)
                throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var card = handOf(table.sessionId(), leader).get(0);

            return playCard(table.sessionId(), leader.playerToken(),
                    annotatedRequest(card.cardId(), components, componentLength, noteLength));
        }
    }

    @Nested
    @DisplayName("Resolving a trick")
    class ResolvingATrick {

        @Test
        @DisplayName("refuses a trick nobody has played into")
        void shouldRefuseWithNoTrickOpen() throws Exception {
            final var table = dealtTable();

            final var nothing = resolve(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(nothing.getResponse().getStatus())
                    .as("there is nothing to resolve, which is a state problem")
                    .isEqualTo(409);
            assertProblemJson(nothing);
        }

        @Test
        @DisplayName("refuses a trick that is still going round")
        void shouldRefuseAnIncompleteTrick() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            playCard(table.sessionId(), leader.playerToken(),
                    playRequest(handOf(table.sessionId(), leader).get(0).cardId()));

            final var early = resolve(table.sessionId(), leader.playerToken());

            Assertions.assertThat(early.getResponse().getStatus())
                    .as("two seats have yet to play, so the winner is not yet determined")
                    .isEqualTo(409);
            assertProblemJson(early);
        }

        @Test
        @DisplayName("names the winning seat once every seat has played")
        void shouldNameTheWinningSeat() throws Exception {
            final var table = dealtTable();
            final var plays = playWholeTrick(table);

            final var resolved = resolve(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
            final var document = JsonPath.parse(resolved.getResponse().getContentAsString());
            Assertions.assertThat(document.read("$.plays", List.class))
                    .as("a trick holds one card from each seat still holding cards")
                    .hasSize(PLAYERS);
            Assertions.assertThat((Integer) document.read("$.winningSeat"))
                    .as("the highest card of the led suit takes the trick unless a trump was played: %s", plays)
                    .isEqualTo(expectedWinner(plays));
        }

        @Test
        @DisplayName("lets a participant resolve, because there is nothing to decide")
        void shouldLetAParticipantResolve() throws Exception {
            final var table = dealtTable();
            playWholeTrick(table);

            final var resolved = resolve(table.sessionId(), table.seats().get(1).playerToken());

            Assertions.assertThat(resolved.getResponse().getStatus())
                    .as("the cards already played settle the outcome, so the table need not wait on one person")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("refuses to resolve the same trick twice")
        void shouldRefuseASecondResolution() throws Exception {
            final var table = dealtTable();
            playWholeTrick(table);
            resolve(table.sessionId(), table.facilitator().playerToken());

            final var again = resolve(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(again.getResponse().getStatus())
                    .as("a resolved trick has an answer already, and resolving it again would invite a second one")
                    .isEqualTo(409);
            assertProblemJson(again);
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAMissingToken() throws Exception {
            final var table = dealtTable();
            playWholeTrick(table);

            final var anonymous = resolve(table.sessionId(), null);

            assertProblem(anonymous, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session")
        void shouldReportAnUnknownSession() throws Exception {
            final var table = dealtTable();

            final var nowhere = resolve(UUID.randomUUID().toString(), table.facilitator().playerToken());

            assertProblem(nowhere, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = dealtTable();

            final var refused = resolve("not-a-uuid", table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }
    }

    @Nested
    @DisplayName("Reading the state of play")
    class ReadingTheStateOfPlay {

        @Test
        @DisplayName("names the opening leader before any card is played")
        void shouldNameTheOpeningLeader() throws Exception {
            final var table = dealtTable();
            final var leaderSeat = leaderSeatOf(table);

            final var state = trickState(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(state.getResponse().getStatus()).isEqualTo(200);
            final var document = JsonPath.parse(state.getResponse().getContentAsString());
            Assertions.assertThat((Integer) document.read("$.seatToPlay"))
                    .as("the holder of the lowest Tampering card leads, and the session row records that seat")
                    .isEqualTo(leaderSeat);
            Assertions.assertThat(state.getResponse().getContentAsString())
                    .as("no card has been led, so there is no trick to report yet")
                    .doesNotContain("\"trick\"")
                    .contains("\"complete\":false")
                    .contains("\"handComplete\":false");
        }

        @Test
        @DisplayName("names the seat still to play once the trick is under way")
        void shouldNameTheSeatStillToPlay() throws Exception {
            final var table = dealtTable();
            final var leaderSeat = leaderSeatOf(table);
            final var leader = table.seats().get(leaderSeat);
            playCard(table.sessionId(), leader.playerToken(),
                    playRequest(handOf(table.sessionId(), leader).get(0).cardId()));

            final var state = trickState(table.sessionId(), leader.playerToken());

            final var document = JsonPath.parse(state.getResponse().getContentAsString());
            Assertions.assertThat((Integer) document.read("$.seatToPlay"))
                    .as("turn order runs clockwise from the leader")
                    .isEqualTo((leaderSeat + 1) % PLAYERS);
            Assertions.assertThat((Integer) document.read("$.trick.sequence"))
                    .as("the first card led opens the first trick")
                    .isEqualTo(1);
            Assertions.assertThat((Boolean) document.read("$.complete")).isFalse();
        }

        @Test
        @DisplayName("names no seat while a complete trick waits to be resolved")
        void shouldNameNoSeatWhileTheTrickAwaitsResolution() throws Exception {
            final var table = dealtTable();
            playWholeTrick(table);

            final var state = trickState(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(state.getResponse().getContentAsString())
                    .as("every seat has played, so nothing may be played and no seat leads yet")
                    .contains("\"complete\":true")
                    .doesNotContain("seatToPlay")
                    .doesNotContain("nextLeaderSeat")
                    .contains("\"handComplete\":false");
        }

        @Test
        @DisplayName("names the next leader once the trick is resolved, and the two authorities agree")
        void shouldNameTheNextLeaderOnceResolved() throws Exception {
            final var table = dealtTable();
            final var plays = playWholeTrick(table);
            resolve(table.sessionId(), table.facilitator().playerToken());

            final var state = trickState(table.sessionId(), table.facilitator().playerToken());

            final var document = JsonPath.parse(state.getResponse().getContentAsString());
            Assertions.assertThat((Integer) document.read("$.nextLeaderSeat"))
                    .as("the winner still holds twenty-five cards, so the winner leads: %s", plays)
                    .isEqualTo(expectedWinner(plays));
            Assertions.assertThat((Integer) document.read("$.seatToPlay"))
                    .as("the session row and the cards are separate authorities, and here they must agree")
                    .isEqualTo(document.read("$.nextLeaderSeat"));
        }

        /**
         * Reads the state of play and asserts it names no card any seat still holds.
         *
         * <p>{@code TrickStateDto} has no field that could carry one, so this passes today by
         * construction — which is the reason to assert it rather than trust it. That any seated
         * player may read this without learning another seat's hand is a claim about the shape of
         * the response, and a field added to {@code TrickDto}, or a {@code seatsHoldingCards}
         * accessor promoted onto {@code TrickState}, would break it without failing anything else
         * in this class.
         *
         * <p>The led card is asserted present as well. Without that, the test would also pass
         * against a response that named no cards at all, including the ones it is meant to publish.
         */
        @Test
        @DisplayName("names no card any seat still holds")
        void shouldNameNoCardStillHeld() throws Exception {
            final var table = dealtTable();
            final var leaderSeat = leaderSeatOf(table);
            final var leader = table.seats().get(leaderSeat);
            final var led = handOf(table.sessionId(), leader).get(0);
            playCard(table.sessionId(), leader.playerToken(), playRequest(led.cardId()));

            final var stillHeld = new java.util.ArrayList<String>();
            for (final var seat : table.seats()) {
                for (final var card : handOf(table.sessionId(), seat)) {
                    stillHeld.add(card.cardId());
                }
            }

            final var body = trickState(table.sessionId(), leader.playerToken())
                    .getResponse()
                    .getContentAsString();

            Assertions.assertThat(stillHeld)
                    .as("the seats still hold cards, so there is something here to leak")
                    .isNotEmpty();
            Assertions.assertThat(body)
                    .as("the card that was played is face up and belongs in the response")
                    .contains(led.cardId());
            Assertions.assertThat(body)
                    .as("no card still in a hand may appear in a response every seat may read")
                    .doesNotContain(stillHeld.toArray(new String[0]));
        }

        @Test
        @DisplayName("plays the whole hand out, then reports it complete and refuses another card")
        void shouldReportAHandPlayedToItsEnd() throws Exception {
            final var table = dealtTable();
            final var facilitator = table.facilitator().playerToken();

            // Seventy-eight cards over three seats is twenty-six each, so every seat runs out on the same trick
            // and no seat is ever handed a lead it cannot use. Whose turn it is is taken from the route under
            // test rather than derived from the cards, so playing the hand out is also a long exercise of it.
            for (int trick = 1; trick <= CARDS_EACH; trick++) {
                final var opening = JsonPath.parse(
                        trickState(table.sessionId(), facilitator).getResponse().getContentAsString());
                final int leaderSeat = (Integer) opening.read("$.seatToPlay");
                String ledSuit = null;

                for (int seatsPlayed = 0; seatsPlayed < PLAYERS; seatsPlayed++) {
                    final var player = table.seats().get((leaderSeat + seatsPlayed) % PLAYERS);
                    final var card = choose(handOf(table.sessionId(), player), ledSuit);
                    final var played = playCard(table.sessionId(), player.playerToken(), playRequest(card.cardId()));
                    Assertions.assertThat(played.getResponse().getStatus())
                            .as("trick %d, seat %d should accept a legal play", trick, player.seatOrder())
                            .isEqualTo(201);
                    ledSuit = ledSuit == null ? card.suit() : ledSuit;
                }

                Assertions.assertThat(resolve(table.sessionId(), facilitator).getResponse().getStatus())
                        .as("trick %d should resolve once every seat has played", trick)
                        .isEqualTo(200);
            }

            final var spent = trickState(table.sessionId(), facilitator);

            Assertions.assertThat(spent.getResponse().getContentAsString())
                    .as("every card dealt has been played, so no seat leads and no seat may play")
                    .contains("\"handComplete\":true")
                    .contains("\"complete\":true")
                    .doesNotContain("seatToPlay")
                    .doesNotContain("nextLeaderSeat");

            final var refused = playCard(table.sessionId(), facilitator,
                    playRequest(java.util.UUID.randomUUID().toString()));

            assertProblem(refused, 409, "Hand complete");
        }

        @Test
        @DisplayName("refuses before the deal, because there is no state of play yet")
        void shouldRefuseBeforeTheDeal() throws Exception {
            final var table = startedTable();

            final var refused = trickState(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("nothing the caller named is missing, so this is a state problem and not a 404")
                    .isEqualTo(409);
            assertProblemJson(refused);
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAnAnonymousCaller() throws Exception {
            final var table = dealtTable();

            final var refused = trickState(table.sessionId(), null);

            assertProblem(refused, 403, "Player not recognised");
        }

        @Test
        @DisplayName("refuses a token minted for another session")
        void shouldRefuseAForeignToken() throws Exception {
            final var table = dealtTable();
            final var elsewhere = seatedTable();

            final var refused = trickState(table.sessionId(), elsewhere.facilitator().playerToken());

            assertProblem(refused, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session")
        void shouldReportAnUnknownSession() throws Exception {
            final var table = dealtTable();

            final var missing = trickState(UUID.randomUUID().toString(), table.facilitator().playerToken());

            Assertions.assertThat(missing.getResponse().getStatus()).isEqualTo(404);
            assertProblemJson(missing);
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = dealtTable();

            final var refused = trickState("not-a-uuid", table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }
    }

    @Nested
    @DisplayName("Two plays racing for one seat")
    class ConcurrentPlays {

        private static final int CONTENDERS = 2;

        @Test
        @DisplayName("accepts one and refuses the other with a conflict, never with a server error")
        void shouldAcceptExactlyOneOfTwoSimultaneousPlays() throws Exception {
            final var table = dealtTable();
            final var leader = table.seats().get(leaderSeatOf(table));
            final var hand = handOf(table.sessionId(), leader);
            final var gate = new CountDownLatch(1);
            final var ready = new CountDownLatch(CONTENDERS);

            final List<Future<MvcResult>> races = new ArrayList<>();
            try (ExecutorService callers = Executors.newFixedThreadPool(CONTENDERS)) {
                for (var contender = 0; contender < CONTENDERS; contender++) {
                    final var card = hand.get(contender).cardId();
                    races.add(callers.submit(() -> {
                        ready.countDown();
                        gate.await();
                        return playCard(table.sessionId(), leader.playerToken(), playRequest(card));
                    }));
                }
                ready.await();
                gate.countDown();
            }

            final var outcomes = races.stream().map(TrickControllerIntegrationTest::completed).toList();
            final var statuses = outcomes.stream()
                    .map(result -> result.getResponse().getStatus())
                    .collect(Collectors.toList());

            Assertions.assertThat(statuses)
                    .as("one play is recorded and the other is refused: the unique constraint decides, not the clock")
                    .containsExactlyInAnyOrder(201, 409);
            Assertions.assertThat(handOf(table.sessionId(), leader))
                    .as("the losing play is rolled back whole, so its card is still in the hand")
                    .hasSize(CARDS_EACH - 1);

            // The status alone would pass for a conflict returned as plain text or as a stack trace.
            // The collision is a constraint violation deep in the adapter, so what earns its keep is
            // that it still reaches the caller as the same problem document as any other refusal.
            //
            // Which rule refuses the loser is the scheduler's choice, and this assertion has been
            // wrong twice by trying to name it. No trick is open when the pair set off, so both take
            // the opening branch, and at least three refusals are correct depending on where the
            // loser is when the winner commits: the sequence constraint while opening the trick
            // ("Trick already open"), the seat constraint while appending to the trick the winner
            // opened ("Already played in this trick"), or the domain itself if the loser re-reads
            // after the commit and finds the lead has passed ("Not your turn"). A fourth may exist.
            //
            // So this asserts the property rather than the enumeration. What a caller is owed is a
            // conflict, as a problem document, naming some rule - and an enumeration of a race is a
            // guess that passes until the machine is faster. The rule that fired is diagnostic, not
            // contractual, so it is interpolated into the failure messages below rather than matched:
            // a diagnostician still learns which rule won the race, without a test asserting it.
            final var loser = outcomes.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no contender was refused"));
            assertProblemJson(loser);
            final var refusal = JsonPath.parse(loser.getResponse().getContentAsString());
            final var refusedBy = refusal.read("$.title", String.class);
            Assertions.assertThat(refusal.read("$.status", Integer.class))
                    .as("the problem document agrees with the status line, refused by: %s", refusedBy)
                    .isEqualTo(409);
            Assertions.assertThat(refusedBy)
                    .as("the refusal names the rule that stopped it rather than leaving the caller to guess")
                    .isNotBlank();
        }
    }

    /**
     * Seats three players in a lobby without starting play.
     *
     * @return the session and its three admissions, indexed by seat
     * @throws Exception if any fixture request fails
     */
    private Table seatedTable() throws Exception {
        final var facilitator = createSession("Ada");
        final var second = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");

        return new Table(facilitator.sessionId(), List.of(facilitator, second, third));
    }

    /**
     * Seats three players and starts play, leaving the deck undealt.
     *
     * @return the started table
     * @throws Exception if any fixture request fails
     */
    private Table startedTable() throws Exception {
        final var table = seatedTable();
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/start")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isOk());

        return table;
    }

    /**
     * Seats three players, starts play and deals the deck.
     *
     * @return a table holding cards
     * @throws Exception if any fixture request fails
     */
    private Table dealtTable() throws Exception {
        final var table = startedTable();
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/deal")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isNoContent());

        return table;
    }

    /**
     * Plays one card from every seat, in turn, so the trick is complete.
     *
     * <p>Each seat follows suit when it can, which is what a client with only its own hand would do. The first seat is
     * the leader, and the rest go clockwise from there.</p>
     *
     * <p>The cards played are returned so a caller can work out for itself which seat ought to take the trick. A test
     * that only knew the trick was complete could assert no more than that the winning seat is a seat, which an
     * implementation naming the same seat every time would satisfy.</p>
     *
     * @param table a dealt table
     * @return what each seat played, keyed by seat order, in the order the cards were played
     * @throws Exception if any play is refused
     */
    private Map<Integer, CardView> playWholeTrick(final Table table) throws Exception {
        final var leaderSeat = leaderSeatOf(table);
        final Map<Integer, CardView> played = new LinkedHashMap<>();
        String ledSuit = null;

        for (var offset = 0; offset < PLAYERS; offset++) {
            final var player = table.seats().get((leaderSeat + offset) % PLAYERS);
            final var card = choose(handOf(table.sessionId(), player), ledSuit);
            final var accepted = playCard(table.sessionId(), player.playerToken(), playRequest(card.cardId()));

            Assertions.assertThat(accepted.getResponse().getStatus())
                    .as("seat %d plays %s", player.seatOrder(), card.cardId())
                    .isEqualTo(201);
            played.put(player.seatOrder(), card);

            if (ledSuit == null) {
                ledSuit = card.suit();
            }
        }

        return played;
    }

    /**
     * Works out which seat ought to take a completed trick, from the cards that were played.
     *
     * <p>This repeats the rule rather than asking the server: the highest Elevation of Privilege beats everything, and
     * with no trump in the trick the highest card of the led suit wins. Ties cannot arise because no card is dealt
     * twice. Deriving the answer independently is the whole value of the assertion.</p>
     *
     * @param plays  what each seat played, keyed by seat order, the first entry being the lead
     * @return the seat that takes the trick
     */
    private static int expectedWinner(final Map<Integer, CardView> plays) {
        final var ledSuit = plays.values().iterator().next().suit();
        final var trumped = plays.entrySet().stream().anyMatch(play -> TRUMP.equals(play.getValue().suit()));
        final var contending = trumped ? TRUMP : ledSuit;

        return plays.entrySet().stream()
                .filter(play -> contending.equals(play.getValue().suit()))
                .max(Comparator.comparingInt(play -> play.getValue().rankValue()))
                .orElseThrow(() -> new AssertionError("no card of the contending suit was played"))
                .getKey();
    }

    /**
     * Picks a card that is legal to play: one of the led suit when the hand holds any, otherwise anything.
     *
     * @param hand    the cards available
     * @param ledSuit the suit led, or {@code null} when this play is the lead
     * @return the card to play
     */
    private static CardView choose(final List<CardView> hand, final String ledSuit) {
        if (ledSuit == null) {
            return hand.get(0);
        }

        return hand.stream().filter(card -> card.suit().equals(ledSuit)).findFirst().orElse(hand.get(0));
    }

    /**
     * Works out which seat leads the opening trick, by reading every hand and finding the lowest Tampering card.
     *
     * <p>Only a test can do this, because only a test holds every player's credential. That is the point: the seat
     * that leads is derived from the cards actually dealt, and no rank is guaranteed to be in play, so a client cannot
     * assume it.</p>
     *
     * @param table a dealt table
     * @return the seat holding the lowest-ranked Tampering card
     * @throws Exception if any hand cannot be read
     */
    private int leaderSeatOf(final Table table) throws Exception {
        var leader = -1;
        var lowest = Integer.MAX_VALUE;

        for (var seat = 0; seat < table.seats().size(); seat++) {
            for (final var card : handOf(table.sessionId(), table.seats().get(seat))) {
                if (TAMPERING.equals(card.suit()) && card.rankValue() < lowest) {
                    lowest = card.rankValue();
                    leader = seat;
                }
            }
        }

        Assertions.assertThat(leader)
                .as("the whole deck is dealt, so some seat holds the lowest Tampering card")
                .isNotNegative();

        return leader;
    }

    /**
     * Reads one player's hand and returns its cards.
     *
     * @param sessionId the session
     * @param player    the player whose hand to read
     * @return the cards the player holds
     * @throws Exception if the read fails
     */
    private List<CardView> handOf(final String sessionId, final Admission player) throws Exception {
        final var body = readHand(sessionId, player.playerToken()).getResponse().getContentAsString();
        final List<Map<String, Object>> cards = JsonPath.parse(body).read("$.cards");

        return cards.stream()
                .map(card -> new CardView((String) card.get("cardId"), (String) card.get("suit"),
                        (Integer) card.get("rankValue")))
                .toList();
    }

    /**
     * Deals the deck.
     *
     * @param sessionId the session
     * @param token     the caller's credential, or {@code null} to send none
     * @return the result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult deal(final String sessionId, final String token) throws Exception {
        return mockMvc.perform(withToken(post(SESSIONS + "/" + sessionId + "/deal"), token)).andReturn();
    }

    /**
     * Reads the caller's own hand.
     *
     * @param sessionId the session
     * @param token     the caller's credential, or {@code null} to send none
     * @return the result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult readHand(final String sessionId, final String token) throws Exception {
        return mockMvc.perform(withToken(get(SESSIONS + "/" + sessionId + "/hand"), token)).andReturn();
    }

    /**
     * Plays a card.
     *
     * @param sessionId the session
     * @param token     the caller's credential, or {@code null} to send none
     * @param body      the request body
     * @return the result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult playCard(final String sessionId, final String token, final String body) throws Exception {
        return mockMvc.perform(withToken(post(SESSIONS + "/" + sessionId + "/plays"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    /**
     * Resolves the current trick.
     *
     * @param sessionId the session
     * @param token     the caller's credential, or {@code null} to send none
     * @return the result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult resolve(final String sessionId, final String token) throws Exception {
        return mockMvc.perform(withToken(post(SESSIONS + "/" + sessionId + "/tricks/current/resolve"), token))
                .andReturn();
    }

    /**
     * Reads the state of play.
     *
     * @param sessionId the session
     * @param token     the caller's credential, or {@code null} to send none
     * @return the result, whatever its status
     * @throws Exception if the request cannot be performed
     */
    private MvcResult trickState(final String sessionId, final String token) throws Exception {
        return mockMvc.perform(withToken(get(SESSIONS + "/" + sessionId + "/tricks/current"), token)).andReturn();
    }

    /**
     * Adds the identity header unless the token is {@code null}, so a null token exercises the missing-credential path.
     *
     * @param request the request being built
     * @param token   the credential, or {@code null}
     * @return the request, with or without the header
     */
    private static MockHttpServletRequestBuilder withToken(final MockHttpServletRequestBuilder request,
            final String token) {
        if (token == null) {
            return request;
        }

        return request.header(SessionController.PLAYER_TOKEN_HEADER, token);
    }

    /**
     * Builds the smallest body the play route accepts.
     *
     * @param cardId the card to play
     * @return a request body naming only the card
     */
    private static String playRequest(final String cardId) {
        return "{\"cardId\":\"%s\",\"threatLinked\":true}".formatted(cardId);
    }

    /**
     * Builds a play request carrying an annotation of the given shape.
     *
     * @param cardId          the card to play
     * @param components      how many component names to send
     * @param componentLength the length of each component name
     * @param noteLength      the length of the note
     * @return the request body
     */
    private static String annotatedRequest(final String cardId, final int components, final int componentLength,
            final int noteLength) {
        final var names = new ArrayList<String>();
        for (var index = 0; index < components; index++) {
            names.add("\"%s\"".formatted("c".repeat(componentLength)));
        }

        return """
                {"cardId":"%s","threatLinked":true,"components":[%s],"notes":"%s"}"""
                .formatted(cardId, String.join(",", names), "n".repeat(noteLength));
    }

    /**
     * Creates a session and admits its facilitator.
     *
     * @param displayName the facilitator's name
     * @return the admission
     * @throws Exception if the request fails
     */
    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return admissionFrom(body);
    }

    /**
     * Joins an existing session by its join code.
     *
     * @param joinCode    the code to join with
     * @param displayName the joining player's name
     * @return the admission
     * @throws Exception if the request fails
     */
    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return admissionFrom(body);
    }

    /**
     * Builds a display-name request body.
     *
     * @param displayName the name to send
     * @return the request body
     */
    private static String nameRequest(final String displayName) {
        return "{\"displayName\":\"%s\"}".formatted(displayName);
    }

    /**
     * Reads an admission out of a response body.
     *
     * <p>The seat is not published beside the credential: it is found in the session state, on the player row the
     * admission names. That is the same two-step a real client makes, and it is why the hand response does not repeat
     * the seat.</p>
     *
     * @param body the response body
     * @return the admission it describes
     */
    private static Admission admissionFrom(final String body) {
        final var document = JsonPath.parse(body);
        final var playerId = document.read("$.playerId", String.class);
        final List<Map<String, Object>> players = document.read("$.session.players");
        final var seatOrder = players.stream()
                .filter(player -> playerId.equals(player.get("playerId")))
                .map(player -> (Integer) player.get("seatOrder"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the admitted player is missing from the session state"));

        return new Admission(document.read("$.session.sessionId", String.class),
                document.read("$.session.joinCode", String.class),
                playerId,
                document.read("$.playerToken", String.class),
                seatOrder);
    }

    /**
     * Asserts a result is an RFC 9457 problem document with the given status and title.
     *
     * @param result the result to inspect
     * @param status the status expected
     * @param title  the title expected
     * @throws Exception if the body cannot be read
     */
    private static void assertProblem(final MvcResult result, final int status, final String title) throws Exception {
        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertProblemJson(result);
        Assertions.assertThat(JsonPath.parse(result.getResponse().getContentAsString()).read("$.title", String.class))
                .isEqualTo(title);
    }

    /**
     * Asserts a result carries a problem document rather than an ordinary body.
     *
     * @param result the result to inspect
     */
    private static void assertProblemJson(final MvcResult result) {
        Assertions.assertThat(result.getResponse().getContentType())
                .as("every refusal is an RFC 9457 problem document")
                .startsWith(PROBLEM_JSON);
    }

    /**
     * Waits for a racing request to finish, restoring the interrupt flag if the wait is cut short.
     *
     * @param race the request in flight
     * @return its result
     */
    private static MvcResult completed(final Future<MvcResult> race) {
        try {
            return race.get();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a racing play", interrupted);
        } catch (final ExecutionException failed) {
            throw new IllegalStateException("a racing play threw", failed);
        }
    }

    /**
     * A player admitted to a session, and the credential minted for them.
     *
     * @param sessionId   the session joined
     * @param joinCode    the code others join with
     * @param playerId    the player's identifier
     * @param playerToken the player's credential
     * @param seatOrder   the seat assigned at admission
     */
    private record Admission(String sessionId, String joinCode, String playerId, String playerToken, int seatOrder) {
    }

    /**
     * A session and the players seated at it, indexed by seat.
     *
     * @param sessionId the session
     * @param seats     the admissions, seat 0 first
     */
    private record Table(String sessionId, List<Admission> seats) {

        /**
         * Returns the player who created the session, who is the only facilitator.
         *
         * @return seat zero
         */
        Admission facilitator() {
            return seats.get(0);
        }
    }

    /**
     * The part of a card a test needs: what to play, which suit it follows and how high it is.
     *
     * @param cardId    the identifier to send
     * @param suit      the STRIDE category, as published
     * @param rankValue the rank, ace high
     */
    private record CardView(String cardId, String suit, int rankValue) {
    }
}
