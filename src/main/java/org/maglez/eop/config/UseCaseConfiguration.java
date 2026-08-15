package org.maglez.eop.config;

import java.time.Clock;
import org.maglez.eop.usecase.CardRepository;
import org.maglez.eop.usecase.CreateSessionUseCase;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.DeckShuffler;
import org.maglez.eop.usecase.GetCardUseCase;
import org.maglez.eop.usecase.GetScoreUseCase;
import org.maglez.eop.usecase.GetSessionStateUseCase;
import org.maglez.eop.usecase.GetTrickStateUseCase;
import org.maglez.eop.usecase.HandRepository;
import org.maglez.eop.usecase.IdentifierGenerator;
import org.maglez.eop.usecase.IdentityTokenGenerator;
import org.maglez.eop.usecase.JoinAttemptLimiter;
import org.maglez.eop.usecase.JoinCodeGenerator;
import org.maglez.eop.usecase.JoinSessionUseCase;
import org.maglez.eop.usecase.ListCardsUseCase;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ReadOwnHandUseCase;
import org.maglez.eop.usecase.ResolvePlayerUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.maglez.eop.usecase.SessionEventPublisher;
import org.maglez.eop.usecase.SessionRepository;
import org.maglez.eop.usecase.StartSessionUseCase;
import org.maglez.eop.usecase.TrickRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use case layer into the Spring context.
 *
 * <p>This class exists so that the use cases do not have to. Annotating them with
 * {@code @Service} would be simpler by one file and would put a Spring import
 * into the layer that AGENTS.md requires to have none, which is the whole point
 * of the boundary: the use cases can be constructed and tested with {@code new},
 * and the container is a delivery detail declared out here.
 */
@Configuration
public class UseCaseConfiguration {

    /**
     * Declares the clock the session use cases read the current instant from.
     *
     * <p>A clock is a dependency rather than a call to {@code Instant.now()} so that
     * a test can decide what time it is. Every timestamp this story writes — when a
     * lobby opened, when a player took a seat, when play began — comes from here, so
     * a fixed clock makes those assertions exact instead of approximate.
     *
     * <p>UTC, not the system zone: the database columns are {@code TIMESTAMP WITH TIME
     * ZONE} and the deployment target's zone is not a property anything should depend on.
     *
     * @return a UTC clock reading the system time
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Declares the list-cards use case.
     *
     * @param cardRepository the port implementation supplied by the persistence adapter
     * @return the list-cards use case
     */
    @Bean
    public ListCardsUseCase listCardsUseCase(final CardRepository cardRepository) {
        return new ListCardsUseCase(cardRepository);
    }

    /**
     * Declares the get-card use case.
     *
     * @param cardRepository the port implementation supplied by the persistence adapter
     * @return the get-card use case
     */
    @Bean
    public GetCardUseCase getCardUseCase(final CardRepository cardRepository) {
        return new GetCardUseCase(cardRepository);
    }

    /**
     * Declares the create-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * <p>Gated as well as the controller, and the reason is that the controller alone was
     * never enough. Until EOP-48 the four lifecycle use cases were registered
     * unconditionally, so a mistake in the flag's spelling had nothing to fail against:
     * the beans that write a session existed regardless, and only the route was withheld.
     * Withholding every caller of {@link SessionRepository} that opens or mutates a
     * session is what makes the flag's off position a property of the context rather than
     * of the request mapping — the same arrangement the trick-play use cases below use.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param identifierGenerator the UUID version 7 generator (ADR-018)
     * @param joinCodeGenerator the cryptographically secure join code generator
     * @param identityTokenGenerator the cryptographically secure identity token generator
     * @param clock the clock the created timestamps are read from
     * @return the create-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public CreateSessionUseCase createSessionUseCase(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final JoinCodeGenerator joinCodeGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final Clock clock) {
        return new CreateSessionUseCase(
                sessionRepository, identifierGenerator, joinCodeGenerator, identityTokenGenerator, clock);
    }

    /**
     * Declares the join-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param identifierGenerator the UUID version 7 generator (ADR-018)
     * @param identityTokenGenerator the cryptographically secure identity token generator
     * @param joinAttemptLimiter the rate limiter that makes a six character code safe (ADR-019)
     * @param sessionEventPublisher the transport that announces a change to connected clients
     * @param clock the clock the joined timestamp is read from
     * @return the join-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public JoinSessionUseCase joinSessionUseCase(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final JoinAttemptLimiter joinAttemptLimiter,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new JoinSessionUseCase(
                sessionRepository,
                identifierGenerator,
                identityTokenGenerator,
                joinAttemptLimiter,
                sessionEventPublisher,
                clock);
    }

    /**
     * Declares the resolve-player use case, deliberately ungated.
     *
     * <p>Every other lifecycle use case here is behind {@code eop.features.session-lifecycle};
     * this one is not, and the exception is load-bearing. It is a pure lookup — a token in, a
     * named player out, nothing written — and the five trick-play use cases below depend on it
     * too. Gating it on the lifecycle flag would make trick play unsatisfiable whenever the
     * lobby flag were off and trick play on, turning a withheld feature into a context that
     * will not start. Same reasoning as the ungated {@link DeckShuffler}: a dependency shared
     * across two flags belongs to neither.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @return the resolve-player use case
     */
    @Bean
    public ResolvePlayerUseCase resolvePlayerUseCase(final SessionRepository sessionRepository) {
        return new ResolvePlayerUseCase(sessionRepository);
    }

    /**
     * Declares the get-session-state use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @return the get-session-state use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public GetSessionStateUseCase getSessionStateUseCase(final ResolvePlayerUseCase resolvePlayerUseCase) {
        return new GetSessionStateUseCase(resolvePlayerUseCase);
    }

    /**
     * Declares the start-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param sessionEventPublisher the transport that announces a change to connected clients
     * @param clock the clock the started timestamp is read from
     * @return the start-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public StartSessionUseCase startSessionUseCase(
            final SessionRepository sessionRepository,
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new StartSessionUseCase(sessionRepository, resolvePlayerUseCase, sessionEventPublisher, clock);
    }

    /**
     * Declares the deal-hands use case, behind {@code eop.features.trick-play}.
     *
     * <p>The flag is on the bean rather than on a controller because this slice ships
     * no route. The persistence slice before it left five trick-play tables reachable
     * by anything that injects {@link HandRepository} or {@link TrickRepository}, and
     * this is the slice that adds the first such beans, so gating them here is what
     * makes the containment claim true rather than merely intended: with the flag off,
     * no bean exists that calls the ports which write a hand, a trick or a
     * play. The adapter implementing those ports is an unconditional {@code @Repository} and is
     * created either way; what the flag withholds is every caller of it. The route Slice D adds
     * is gated on the same flag (ADR-013).
     *
     * <p>{@link DeckShuffler} is deliberately not gated. It is stateless, reaches no
     * table, and gating it would only turn a missing feature into an unsatisfied
     * dependency somewhere further away from the cause.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param cardRepository the port the whole deck is read through
     * @param deckShuffler the port that randomises the deck before it is dealt
     * @param handRepository the port the deal is recorded through
     * @param identifierGenerator the source of the hand identifiers
     * @param sessionEventPublisher the transport that announces the deal to connected clients
     * @param clock the clock the dealt timestamp is read from
     * @return the deal-hands use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public DealHandsUseCase dealHandsUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final CardRepository cardRepository,
            final DeckShuffler deckShuffler,
            final HandRepository handRepository,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new DealHandsUseCase(
                resolvePlayerUseCase,
                cardRepository,
                deckShuffler,
                handRepository,
                identifierGenerator,
                sessionEventPublisher,
                clock);
    }

    /**
     * Declares the read-own-hand use case, behind {@code eop.features.trick-play}.
     *
     * <p>Gated with the writers even though it only reads. The flag is meant to withhold trick play
     * entirely, and a read of a hand is trick play: with the flag off there are no hands to read, so
     * the bean would exist only to answer 409 to every caller.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the caller's own hand is read through
     * @return the read-own-hand use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public ReadOwnHandUseCase readOwnHandUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase, final HandRepository handRepository) {
        return new ReadOwnHandUseCase(resolvePlayerUseCase, handRepository);
    }

    /**
     * Declares the play-card use case, behind {@code eop.features.trick-play}.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands and the current leader seat are read through
     * @param trickRepository the port a trick is opened and a play appended through
     * @param cardRepository the port the played card is resolved through
     * @param identifierGenerator the source of the trick and play identifiers
     * @param sessionEventPublisher the transport that announces the play to connected clients
     * @param clock the clock the played timestamp is read from
     * @return the play-card use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public PlayCardUseCase playCardUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final CardRepository cardRepository,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new PlayCardUseCase(
                resolvePlayerUseCase,
                handRepository,
                trickRepository,
                cardRepository,
                identifierGenerator,
                sessionEventPublisher,
                clock);
    }

    /**
     * Declares the trick-state use case, behind {@code eop.features.trick-play}.
     *
     * <p>Reads two ports because the answer needs both: the trick in front of the players, and the
     * hands that decide which seats still hold cards. A use case is where two aggregates are put
     * together, so the web adapter never has to know how they relate.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands and the current leader seat are read through
     * @param trickRepository the port the current trick is read through
     * @return the trick-state use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public GetTrickStateUseCase getTrickStateUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository) {
        return new GetTrickStateUseCase(resolvePlayerUseCase, handRepository, trickRepository);
    }

    /**
     * Declares the resolve-trick use case, behind {@code eop.features.trick-play}.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands are read through
     * @param trickRepository the port the resolution is recorded through
     * @param sessionEventPublisher the transport that announces the resolution to connected clients
     * @param clock the clock the resolved timestamp is read from
     * @return the resolve-trick use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public ResolveTrickUseCase resolveTrickUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new ResolveTrickUseCase(
                resolvePlayerUseCase, handRepository, trickRepository, sessionEventPublisher, clock);
    }

    /**
     * Declares the score use case, behind {@code eop.features.trick-play}.
     *
     * <p>Two collaborators, not three. Resolving the credential already yields the session, and a
     * session carries its own players, so the seated players arrive without a second read and the
     * only port here is the one holding the tricks.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player and their session
     * @param trickRepository      the port the session's tricks are read through
     * @return the score use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public GetScoreUseCase getScoreUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final TrickRepository trickRepository) {
        return new GetScoreUseCase(resolvePlayerUseCase, trickRepository);
    }
}
