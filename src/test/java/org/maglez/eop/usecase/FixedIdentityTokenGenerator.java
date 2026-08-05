package org.maglez.eop.usecase;

/**
 * Identity token generator that always returns the same token.
 *
 * <p>Deliberately constant. A test needs the plaintext in order to hash it and
 * compare, and the property worth asserting is that the token is drawn exactly once
 * per admission even when the write is retried — which the issue count records
 * without the value having to vary.
 */
final class FixedIdentityTokenGenerator implements IdentityTokenGenerator {

    private final String token;

    private int issued;

    FixedIdentityTokenGenerator(final String token) {
        this.token = token;
    }

    @Override
    public String nextToken() {
        issued++;
        return token;
    }

    /**
     * @return how many tokens were handed out
     */
    int issued() {
        return issued;
    }
}
