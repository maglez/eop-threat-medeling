package org.maglez.eop.config;

import org.maglez.eop.usecase.CardRepository;
import org.maglez.eop.usecase.GetCardUseCase;
import org.maglez.eop.usecase.ListCardsUseCase;
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
}
