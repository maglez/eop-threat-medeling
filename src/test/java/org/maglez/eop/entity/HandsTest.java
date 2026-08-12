package org.maglez.eop.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.DeckFixture.card;
import static org.maglez.eop.entity.DeckFixture.fullDeck;
import static org.maglez.eop.entity.DeckFixture.seats;
import static org.maglez.eop.entity.HandBuilder.aHand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Hands")
class HandsTest {

    private static Hand handAt(final int seat, final Card... cards) {
        return aHand()
                .withHandId(new UUID(800, seat))
                .withPlayerId(new UUID(700, seat))
                .withCards(cards)
                .build();
    }

    private static Map<Integer, Hand> at(final Hand... hands) {
        final Map<Integer, Hand> bySeat = new LinkedHashMap<>();
        for (int seat = 0; seat < hands.length; seat++) {
            bySeat.put(seat, hands[seat]);
        }
        return bySeat;
    }

    @Nested
    @DisplayName("deals the whole deck out, putting the remainder on the lowest seats")
    class Dealing {

        @Test
        @DisplayName("three players hold twenty-six cards each, because seventy-eight divides evenly")
        void shouldDealEvenlyToThree() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThat(hands.handOf(0).size()).isEqualTo(26);
            assertThat(hands.handOf(1).size()).isEqualTo(26);
            assertThat(hands.handOf(2).size()).isEqualTo(26);
        }

        @Test
        @DisplayName("six players hold thirteen cards each")
        void shouldDealEvenlyToSix() {
            final Hands hands = Hands.deal(fullDeck(), seats(6));

            assertThat(hands.seats()).containsExactly(0, 1, 2, 3, 4, 5);
            hands.seats().forEach(seat -> assertThat(hands.handOf(seat).size()).isEqualTo(13));
        }

        @Test
        @DisplayName("four players hold twenty, twenty, nineteen and nineteen, so the last trick is short")
        void shouldPutTheRemainderOnTheLowestSeatsAtFour() {
            final Hands hands = Hands.deal(fullDeck(), seats(4));

            assertThat(List.of(
                            hands.handOf(0).size(),
                            hands.handOf(1).size(),
                            hands.handOf(2).size(),
                            hands.handOf(3).size()))
                    .containsExactly(20, 20, 19, 19);
        }

        @Test
        @DisplayName("five players hold sixteen, sixteen, sixteen, fifteen and fifteen")
        void shouldPutTheRemainderOnTheLowestSeatsAtFive() {
            final Hands hands = Hands.deal(fullDeck(), seats(5));

            assertThat(List.of(
                            hands.handOf(0).size(),
                            hands.handOf(1).size(),
                            hands.handOf(2).size(),
                            hands.handOf(3).size(),
                            hands.handOf(4).size()))
                    .containsExactly(16, 16, 16, 15, 15);
        }

        @Test
        @DisplayName("every card is dealt and none is dealt twice, because there is no draw pile")
        void shouldDealEveryCardExactlyOnce() {
            final Hands hands = Hands.deal(fullDeck(), seats(5));

            final List<UUID> dealt = new ArrayList<>();
            hands.handsBySeat()
                    .values()
                    .forEach(hand -> hand.cards().forEach(dealtCard -> dealt.add(dealtCard.cardId())));

            assertThat(hands.totalCards()).isEqualTo(78);
            assertThat(dealt).hasSize(78).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("deals the same way twice, because the order given is the order dealt")
        void shouldBeDeterministic() {
            assertThat(Hands.deal(fullDeck(), seats(4))).isEqualTo(Hands.deal(fullDeck(), seats(4)));
        }

        @Test
        @DisplayName("takes the seats in ascending order however they were listed")
        void shouldSortSeatsBeforeDealing() {
            final List<Hands.Seat> reversed = new ArrayList<>(seats(4));
            java.util.Collections.reverse(reversed);

            assertThat(Hands.deal(fullDeck(), reversed)).isEqualTo(Hands.deal(fullDeck(), seats(4)));
        }

        @Test
        @DisplayName("refuses to deal to fewer players than the game needs")
        void shouldRejectTooFewSeats() {
            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(fullDeck(), seats(2)));
        }

        @Test
        @DisplayName("refuses to deal to more players than the table seats")
        void shouldRejectTooManySeats() {
            final List<Hands.Seat> tooMany = new ArrayList<>(seats(6));
            tooMany.add(new Hands.Seat(0, new UUID(700, 99), new UUID(800, 99)));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(fullDeck(), tooMany));
        }

        @Test
        @DisplayName("refuses a deck too small to give every player a card")
        void shouldRejectADeckSmallerThanTheTable() {
            final List<Card> twoCards = List.of(
                    card(StrideCategory.TAMPERING, Rank.TWO), card(StrideCategory.SPOOFING, Rank.THREE));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(twoCards, seats(3)));
        }

        @Test
        @DisplayName("refuses a null deck or null seats")
        void shouldRejectNulls() {
            assertThatNullPointerException().isThrownBy(() -> Hands.deal(null, seats(3)));
            assertThatNullPointerException().isThrownBy(() -> Hands.deal(fullDeck(), null));
        }
    }

    @Nested
    @DisplayName("derives the opening lead from the cards actually dealt")
    class OpeningLead {

        @Test
        @DisplayName("the seat holding the lowest Tampering card dealt leads")
        void shouldLeadFromTheLowestTamperingCard() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.NINE)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.FOUR)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.KING))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("the two of Tampering leads when the deck holds one, as the seeded deck does")
        void shouldLeadFromTheTwoWhenDealt() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.THREE)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.FIVE)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.TWO))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(2);
        }

        @Test
        @DisplayName("the three of Tampering leads when no two was dealt, as in the printed deck")
        void shouldLeadFromTheThreeWhenNoTwoWasDealt() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.SEVEN)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.THREE)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.QUEEN))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("ignores lower cards of every other suit, because Tampering is where the game starts")
        void shouldIgnoreOtherSuits() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.SPOOFING, Rank.TWO)),
                    handAt(1, card(StrideCategory.REPUDIATION, Rank.TWO)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.ACE))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(2);
        }

        @Test
        @DisplayName("fails loudly when no Tampering card was dealt at all, rather than picking a leader")
        void shouldRejectADealWithNoTamperingCard() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.SPOOFING, Rank.TWO)),
                    handAt(1, card(StrideCategory.REPUDIATION, Rank.THREE)),
                    handAt(2, card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.ACE))));

            assertThatExceptionOfType(NoTamperingCardDealtException.class)
                    .isThrownBy(hands::openingLeaderSeat)
                    .satisfies(thrown -> assertThat(thrown.cardsDealt()).isEqualTo(3));
        }

        @Test
        @DisplayName("derives a leader from a real deal such that no other seat holds a lower tampering card")
        void shouldDeriveALeaderFromARealDeal() {
            final Hands hands = Hands.deal(fullDeck(), seats(4));

            final int leader = hands.openingLeaderSeat();

            final Card led = hands.handOf(leader).lowestOf(StrideCategory.TAMPERING).orElseThrow();
            assertThat(hands.seats())
                    .allSatisfy(seat -> hands.handOf(seat)
                            .lowestOf(StrideCategory.TAMPERING)
                            .ifPresent(lowest -> assertThat(led.rank().beats(lowest.rank())).isFalse()));
        }
    }

    @Nested
    @DisplayName("rejects an impossible set of hands, including one rebuilt from storage")
    class Validation {

        @Test
        @DisplayName("a seat beyond the table")
        void shouldRejectSeatBeyondTable() {
            final Map<Integer, Hand> beyond = new HashMap<>();
            beyond.put(GameSession.MAXIMUM_PLAYERS, handAt(0, card(StrideCategory.TAMPERING, Rank.TWO)));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(beyond));
        }

        @Test
        @DisplayName("no hands at all")
        void shouldRejectNoHands() {
            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(Map.of()));
        }

        @Test
        @DisplayName("one player holding two hands")
        void shouldRejectOnePlayerWithTwoHands() {
            final Map<Integer, Hand> clash = new LinkedHashMap<>();
            clash.put(0, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.TWO)).build());
            clash.put(1, aHand().withHandId(new UUID(800, 1)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.THREE)).build());

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(clash));
        }

        @Test
        @DisplayName("two seats sharing one hand identifier")
        void shouldRejectSharedHandIdentifier() {
            final Map<Integer, Hand> clash = new LinkedHashMap<>();
            clash.put(0, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.TWO)).build());
            clash.put(1, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 1))
                    .withCards(card(StrideCategory.TAMPERING, Rank.THREE)).build());

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(clash));
        }

        @Test
        @DisplayName("the same card dealt to two seats")
        void shouldRejectTheSameCardTwice() {
            final Card shared = card(StrideCategory.TAMPERING, Rank.TWO);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.reconstitute(at(handAt(0, shared), handAt(1, shared))));
        }

        @Test
        @DisplayName("a null map, a null seat or a null hand")
        void shouldRejectNulls() {
            final Map<Integer, Hand> nullHand = new HashMap<>();
            nullHand.put(0, null);

            assertThatNullPointerException().isThrownBy(() -> Hands.reconstitute(null));
            assertThatNullPointerException().isThrownBy(() -> Hands.reconstitute(nullHand));
        }
    }

    @Nested
    @DisplayName("tracks what each seat still holds as cards are played")
    class Playing {

        private final Card tamperingTwo = card(StrideCategory.TAMPERING, Rank.TWO);
        private final Card spoofingKing = card(StrideCategory.SPOOFING, Rank.KING);
        private final Card repudiationFour = card(StrideCategory.REPUDIATION, Rank.FOUR);

        private Hands threeSeats() {
            return Hands.reconstitute(
                    at(handAt(0, tamperingTwo), handAt(1, spoofingKing), handAt(2, repudiationFour)));
        }

        @Test
        @DisplayName("playing a card removes it from that seat's hand only")
        void shouldRemoveThePlayedCard() {
            final Hands after = threeSeats().withCardPlayed(0, tamperingTwo);

            assertThat(after.handOf(0).isEmpty()).isTrue();
            assertThat(after.handOf(1).cards()).containsExactly(spoofingKing);
        }

        @Test
        @DisplayName("and leaves the original hands untouched")
        void shouldNotMutateTheOriginal() {
            final Hands before = threeSeats();

            before.withCardPlayed(0, tamperingTwo);

            assertThat(before.handOf(0).cards()).containsExactly(tamperingTwo);
            assertThat(before.totalCards()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to play a card that seat does not hold, which is what stops a card being played twice")
        void shouldRejectACardTheSeatDoesNotHold() {
            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> threeSeats().withCardPlayed(0, spoofingKing));
        }

        @Test
        @DisplayName("refuses a seat that was never dealt to")
        void shouldRejectAnUnknownSeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> threeSeats().withCardPlayed(4, tamperingTwo));
            assertThatIllegalArgumentException().isThrownBy(() -> threeSeats().handOf(4));
        }

        @Test
        @DisplayName("knows which seats are in the game")
        void shouldKnowItsSeats() {
            assertThat(threeSeats().hasSeat(1)).isTrue();
            assertThat(threeSeats().hasSeat(4)).isFalse();
        }

        @Test
        @DisplayName("reports only the seats still holding cards")
        void shouldReportSeatsHoldingCards() {
            final Hands after = threeSeats().withCardPlayed(1, spoofingKing);

            assertThat(after.seatsHoldingCards()).containsExactly(0, 2);
            assertThat(after.totalCards()).isEqualTo(2);
        }

        @Test
        @DisplayName("is not exhausted until every hand is empty")
        void shouldKnowWhenEveryHandIsEmpty() {
            final Hands partly = threeSeats().withCardPlayed(0, tamperingTwo);
            final Hands exhausted = partly.withCardPlayed(1, spoofingKing).withCardPlayed(2, repudiationFour);

            assertThat(partly.allEmpty()).isFalse();
            assertThat(exhausted.allEmpty()).isTrue();
            assertThat(exhausted.totalCards()).isZero();
        }
    }

    @Nested
    @DisplayName("keeps hands private and exposes no mutable state")
    class Privacy {

        @Test
        @DisplayName("names no card when rendered")
        void shouldNameNoCardInToString() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThat(hands.toString())
                    .contains("seats=3")
                    .contains("cardsRemaining=78")
                    .doesNotContain("TAMPERING")
                    .doesNotContain("There's a way");
        }

        @Test
        @DisplayName("exposes an unmodifiable map of hands")
        void shouldExposeAnUnmodifiableMap() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.handsBySeat().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.seatsHoldingCards().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.seats().clear());
        }

        @Test
        @DisplayName("two identical deals are equal and share a hash code")
        void shouldBeEqualByValue() {
            final Hands one = Hands.deal(fullDeck(), seats(3));
            final Hands other = Hands.deal(fullDeck(), seats(3));

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(Hands.deal(fullDeck(), seats(4)));
        }
    }

    @Nested
    @DisplayName("derives the opening lead from the cards in play, not from a rank written down")
    class DerivedOpeningLead {

        @Test
        @DisplayName("leads from the lowest tampering card dealt even when it is neither a two nor a three")
        void shouldLeadFromWhateverTheLowestTamperingCardIs() {
            final List<Card> deck = List.of(
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.TWO),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.SEVEN),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.JACK),
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.THREE),
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.FOUR));

            final Hands hands = Hands.deal(deck, seats(3));
            final int leader = hands.openingLeaderSeat();

            assertThat(hands.handOf(leader).lowestOf(StrideCategory.TAMPERING))
                    .map(Card::rank)
                    .contains(Rank.SEVEN);
            assertThat(leader).isEqualTo(2);
        }

        @Test
        @DisplayName("picks the lowest even when that card sorts first in its holder's hand")
        void shouldPickTheLowestWhateverTheOrderInHand() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, DeckFixture.card(StrideCategory.TAMPERING, Rank.TWO),
                            DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE)),
                    handAt(1, DeckFixture.card(StrideCategory.TAMPERING, Rank.FIVE)),
                    handAt(2, DeckFixture.card(StrideCategory.SPOOFING, Rank.ACE))));

            assertThat(hands.openingLeaderSeat()).isZero();
        }
    }

    @Nested
    @DisplayName("refuses a seat that is not at the table")
    class SeatBounds {

        @Test
        @DisplayName("a negative seat in a reconstituted set of hands")
        void shouldRejectNegativeSeatKey() {
            final Hand hand = handAt(0, DeckFixture.card(StrideCategory.SPOOFING, Rank.TWO));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.reconstitute(Map.of(-1, hand)));
        }

        @Test
        @DisplayName("a negative seat on a dealing seat")
        void shouldRejectNegativeSeatOrder() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Hands.Seat(-1, new UUID(700, 0), new UUID(800, 0)));
        }
    }

    @Nested
    @DisplayName("refuses a seating that could not have come from a real session")
    class SeatCollisions {

        @Test
        @DisplayName("refuses two seat assignments sharing a seat order, rather than silently dealing one seat two shares")
        void shouldRejectDuplicateSeatOrders() {
            final List<Hands.Seat> colliding = List.of(
                    new Hands.Seat(0, new UUID(700, 0), new UUID(800, 0)),
                    new Hands.Seat(1, new UUID(700, 1), new UUID(800, 1)),
                    new Hands.Seat(1, new UUID(700, 2), new UUID(800, 2)));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.deal(DeckFixture.fullDeck(), colliding))
                    .withMessageContaining("share a seat order");
        }
    }
}
