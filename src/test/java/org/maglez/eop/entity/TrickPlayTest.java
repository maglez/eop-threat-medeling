package org.maglez.eop.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.DeckFixture.card;
import static org.maglez.eop.entity.TrickPlayBuilder.aTrickPlay;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrickPlay")
class TrickPlayTest {

    @Nested
    @DisplayName("rejects a malformed play at construction")
    class Validation {

        @Test
        @DisplayName("null play identifier")
        void shouldRejectNullTrickPlayId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withTrickPlayId(null).build())
                    .withMessageContaining("trickPlayId");
        }

        @Test
        @DisplayName("null player identifier")
        void shouldRejectNullPlayerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withPlayerId(null).build())
                    .withMessageContaining("playerId");
        }

        @Test
        @DisplayName("null card")
        void shouldRejectNullCard() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withCard(null).build())
                    .withMessageContaining("card");
        }

        @Test
        @DisplayName("null play time")
        void shouldRejectNullPlayedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withPlayedAt(null).build())
                    .withMessageContaining("playedAt");
        }

        @Test
        @DisplayName("null component list, because absent components are an empty list")
        void shouldRejectNullComponents() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withComponents(null).build())
                    .withMessageContaining("components");
        }

        @Test
        @DisplayName("a seat below zero")
        void shouldRejectNegativeSeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withSeatOrder(-1).build());
        }

        @Test
        @DisplayName("a seat beyond the table, because there are only six")
        void shouldRejectSeatBeyondTable() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withSeatOrder(GameSession.MAXIMUM_PLAYERS).build());
        }

        @Test
        @DisplayName("more components than the bound allows")
        void shouldRejectTooManyComponents() {
            final List<String> tooMany = IntStream.rangeClosed(0, TrickPlay.MAX_COMPONENTS)
                    .mapToObj(index -> "Component " + index)
                    .toList();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withComponents(tooMany).build());
        }

        @Test
        @DisplayName("a null component name")
        void shouldRejectNullComponentName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withComponents(Arrays.asList("Payments API", null)).build());
        }

        @Test
        @DisplayName("a blank component name, because an empty box names nothing")
        void shouldRejectBlankComponentName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("   ")).build());
        }

        @Test
        @DisplayName("an over-long component name")
        void shouldRejectOverLongComponentName() {
            final String tooLong = "c".repeat(TrickPlay.MAX_COMPONENT_NAME_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of(tooLong)).build());
        }

        @Test
        @DisplayName("an over-long note")
        void shouldRejectOverLongNotes() {
            final String tooLong = "n".repeat(TrickPlay.MAX_NOTES_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withNotes(tooLong).build());
        }
    }

    @Nested
    @DisplayName("normalises what the player typed")
    class Normalisation {

        @Test
        @DisplayName("strips surrounding whitespace from each component name")
        void shouldStripComponentNames() {
            final TrickPlay play = aTrickPlay().withComponents(List.of("  Payments API  ")).build();

            assertThat(play.components()).containsExactly("Payments API");
        }

        @Test
        @DisplayName("treats a blank note as no note at all")
        void shouldTreatBlankNoteAsAbsent() {
            final TrickPlay play = aTrickPlay().withNotes("   ").build();

            assertThat(play.notes()).isNull();
            assertThat(play.notesIfGiven()).isEmpty();
        }

        @Test
        @DisplayName("strips a note that was given")
        void shouldStripNote() {
            final TrickPlay play = aTrickPlay().withNotes("  The token is never checked.  ").build();

            assertThat(play.notesIfGiven()).contains("The token is never checked.");
        }

        @Test
        @DisplayName("exposes an unmodifiable component list")
        void shouldExposeUnmodifiableComponents() {
            final TrickPlay play = aTrickPlay().build();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> play.components().clear());
        }
    }

    @Nested
    @DisplayName("accepts a play that links no threat, because that is a legal outcome and not an error")
    class UnlinkedThreat {

        @Test
        @DisplayName("a play with no threat linked is a valid play")
        void shouldAcceptUnlinkedThreat() {
            final TrickPlay play = aTrickPlay().withThreatLinked(false).withComponents(List.of()).build();

            assertThat(play.threatLinked()).isFalse();
            assertThat(play.components()).isEmpty();
        }

        @Test
        @DisplayName("and still competes for the trick, because failing to link scores nothing rather than forfeiting")
        void shouldStillCompeteForTheTrick() {
            final Card led = card(StrideCategory.SPOOFING, Rank.KING);
            final TrickPlay play = aTrickPlay().withCard(led).withThreatLinked(false).build();

            assertThat(play.canTakeTrick(StrideCategory.SPOOFING)).isTrue();
        }

        @Test
        @DisplayName("a linked threat naming no component is accepted, because a live session must not be blocked")
        void shouldAcceptLinkedThreatWithoutComponents() {
            final TrickPlay play = aTrickPlay().withThreatLinked(true).withComponents(List.of()).build();

            assertThat(play.threatLinked()).isTrue();
            assertThat(play.components()).isEmpty();
        }
    }

    @Nested
    @DisplayName("knows whether its card can take the trick")
    class TakingTheTrick {

        @Test
        @DisplayName("a card of the led suit can")
        void ledSuitCanTake() {
            final TrickPlay play = aTrickPlay().withCard(card(StrideCategory.REPUDIATION, Rank.FOUR)).build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isTrue();
        }

        @Test
        @DisplayName("a trump can, whatever was led")
        void trumpCanTake() {
            final TrickPlay play = aTrickPlay()
                    .withCard(card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.TWO))
                    .build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isTrue();
        }

        @Test
        @DisplayName("a card that is neither cannot, however high it is")
        void offSuitCannotTake() {
            final TrickPlay play = aTrickPlay().withCard(card(StrideCategory.DENIAL_OF_SERVICE, Rank.ACE)).build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isFalse();
        }
    }
}
