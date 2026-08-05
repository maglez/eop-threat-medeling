package org.maglez.eop.adapter.security;

import java.security.SecureRandom;
import org.maglez.eop.entity.JoinCode;
import org.maglez.eop.usecase.JoinCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Draws join codes from a cryptographically secure source.
 *
 * <p>Six characters from a thirty-two symbol alphabet is a keyspace of about one
 * billion, or roughly thirty bits. That is short enough to read aloud on a video
 * call, which was the point, and it is also short enough that the guess rate
 * matters: at thirty bits a code is unguessable only while guessing is slow. The
 * rate limiter on the join endpoint is therefore a primary security control here,
 * not a courtesy (ADR-019).
 *
 * <p>{@link SecureRandom} rather than {@link java.util.Random} for the obvious
 * reason and one less obvious one: an attacker who could predict the sequence would
 * not need to guess at all, and the rate limiter would never see a failed attempt
 * to count.
 *
 * <p>Codes are drawn blind. Uniqueness is enforced by
 * {@code uq_game_session_join_code} and a collision comes back as a rejected
 * insert, because checking whether a code is free before using it is a
 * check-then-act race that two simultaneous facilitators would lose together.
 */
@Component
public class SecureRandomJoinCodeGenerator implements JoinCodeGenerator {

    private final SecureRandom random;

    /**
     * Creates a generator over the platform's default secure random source.
     *
     * <p>The no-argument {@link SecureRandom} constructor is the correct choice
     * rather than a named algorithm: it asks the platform for its strongest
     * available source, and it does not block on Linux the way reading
     * {@code /dev/random} would.
     */
    public SecureRandomJoinCodeGenerator() {
        this.random = new SecureRandom();
    }

    @Override
    public JoinCode nextJoinCode() {
        final var alphabet = JoinCode.ALPHABET;
        final var drawn = new char[JoinCode.LENGTH];
        for (int position = 0; position < drawn.length; position++) {
            drawn[position] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return new JoinCode(new String(drawn));
    }
}
