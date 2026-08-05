package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.config.RealtimeProperties;
import org.maglez.eop.usecase.SessionEvent;
import org.maglez.eop.usecase.SessionEventType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Covers the registry and the heartbeat without a servlet container.
 *
 * <p>Delivery over a real socket is proved elsewhere, by a test that reads the
 * wire. What is worth asserting here is the bookkeeping around it, because every
 * bug this class can have is a leak rather than a visible failure: a subscriber
 * that is never forgotten holds a connection and a slot in a broadcast list for
 * as long as the process lives, and nothing in the application would complain.
 *
 * <p>The heartbeat interval is fifty milliseconds so that a sweep can be observed
 * within a test rather than in fifteen seconds' time.
 */
@DisplayName("SseSessionEventPublisher")
class SseSessionEventPublisherTest {

    private static final Duration BEAT = Duration.ofMillis(50);

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    private static final UUID OTHER_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ee");

    private final SseSessionEventPublisher publisher = new SseSessionEventPublisher(new RealtimeProperties(BEAT));

    @AfterEach
    void stopTheHeartbeatThread() {
        publisher.destroy();
    }

    @Nested
    @DisplayName("registering a listener")
    class Subscribing {

        @Test
        @DisplayName("a subscription is counted against its own session only")
        void shouldCountSubscribersPerSession() {
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(OTHER_SESSION_ID);

            assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(2);
            assertThat(publisher.subscriberCount(OTHER_SESSION_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("a session nobody is watching has no subscribers rather than no entry to ask about")
        void shouldReportZeroForAnUnwatchedSession() {
            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
        }

        @Test
        @DisplayName("a missing session identifier is refused rather than registered under null")
        void shouldRejectANullSessionIdentifier() {
            assertThatNullPointerException()
                    .isThrownBy(() -> publisher.subscribe(null))
                    .withMessageContaining("sessionId");
        }
    }

    @Nested
    @DisplayName("announcing an event")
    class Publishing {

        @Test
        @DisplayName("an event for a session nobody is watching is dropped without complaint")
        void shouldIgnoreAnEventWithNoSubscribers() {
            assertThatCode(() -> publisher.publish(playerJoined())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a live subscriber is kept, because delivery succeeded")
        void shouldKeepALiveSubscriber() {
            publisher.subscribe(SESSION_ID);

            publisher.publish(playerJoined());

            assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(1);
        }

        @Test
        @DisplayName("a departed subscriber is forgotten on the next write and the join that triggered it still succeeds")
        void shouldForgetADepartedSubscriberWithoutFailingTheCaller() {
            final SseEmitter departed = publisher.subscribe(SESSION_ID);
            departed.complete();

            assertThatCode(() -> publisher.publish(playerJoined())).doesNotThrowAnyException();

            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
        }

        @Test
        @DisplayName("a missing event is refused")
        void shouldRejectANullEvent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> publisher.publish(null))
                    .withMessageContaining("event");
        }

        private SessionEvent playerJoined() {
            return new SessionEvent(SessionEventType.PLAYER_JOINED, SESSION_ID, Instant.parse("2026-02-01T09:30:00Z"));
        }
    }

    @Nested
    @DisplayName("keeping the list honest")
    class Heartbeat {

        @Test
        @DisplayName("a peer that went away is discovered by the heartbeat, with no event to prompt it")
        void shouldDiscoverADepartedPeerUnprompted() {
            final SseEmitter departed = publisher.subscribe(SESSION_ID);
            departed.complete();

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(BEAT)
                    .untilAsserted(() -> assertThat(publisher.subscriberCount(SESSION_ID)).isZero());
        }

        @Test
        @DisplayName("a live subscriber survives repeated sweeps")
        void shouldLeaveALiveSubscriberAlone() throws InterruptedException {
            publisher.subscribe(SESSION_ID);

            Thread.sleep(BEAT.toMillis() * 4);

            assertThat(publisher.subscriberCount(SESSION_ID)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("losing every listener")
    class Forgetting {

        @Test
        @DisplayName("forgetting everyone leaves no subscriber for any session")
        void shouldDropEveryRegistration() {
            publisher.subscribe(SESSION_ID);
            publisher.subscribe(OTHER_SESSION_ID);

            publisher.forgetEveryone();

            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
            assertThat(publisher.subscriberCount(OTHER_SESSION_ID)).isZero();
        }

        @Test
        @DisplayName("shutting down closes every stream instead of leaving clients waiting on a dead process")
        void shouldCompleteEveryStreamOnShutdown() {
            final SseEmitter stream = publisher.subscribe(SESSION_ID);

            publisher.destroy();

            assertThat(publisher.subscriberCount(SESSION_ID)).isZero();
            assertThatThrownBy(() -> stream.send(SseEmitter.event().comment("after shutdown")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
