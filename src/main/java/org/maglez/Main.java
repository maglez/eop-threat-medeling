package org.maglez;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring Boot entry point, and the only class outside {@code org.maglez.eop} that carries framework annotations.
 *
 * <p>The annotations here decide four things that no other class can switch on: component scanning rooted at this
 * package, binding of every {@code @ConfigurationProperties} class found beneath it, the scheduler that drives the
 * session sweep, and the {@code /health} endpoint below.
 *
 * <p>{@code /health} is deliberately defined here rather than in {@code org.maglez.eop.adapter.web}. It is the
 * liveness probe the deployment pipeline and {@code ui/Caddyfile} both reach for, it answers before any domain
 * component is involved, and keeping it on the entry point means it cannot be broken by a change to the game's
 * adapter layer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@RestController
public class Main {

    /**
     * Creates the application class.
     *
     * <p>Spring instantiates this reflectively, so the constructor cannot be private. It is written out rather than
     * left implicit only so that it can be documented.
     */
    public Main() {
        // Nothing to initialise: state lives in the beans Spring assembles, not on the entry point.
    }

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed through to Spring Boot unchanged so that
     *     {@code --spring.profiles.active} and property overrides work as usual
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    /**
     * Reports that the process is up and serving HTTP.
     *
     * <p>This is a liveness check, not a readiness check: it deliberately does not touch the database or any use
     * case, so a {@code 200} here means the web layer is answering and nothing more. Do not extend it into a
     * dependency check — a probe that fails when the database is briefly unavailable would have the deployment
     * restart a container that was working.
     *
     * <p>It is documented in {@code docs/api/openapi.yml} alongside the game's own endpoints even though it is not
     * under {@code /api/v1}, because {@code OpenApiContractDriftTest} compares the whole surface Spring serves
     * against that file and carries no path exclusions. Leaving it undocumented would fail the build.
     *
     * @return the fixed body {@code OK}
     */
    @GetMapping("/health")
    @Operation(summary = "Liveness probe",
            description = "Answers 200 with the body OK whenever the process is up and serving HTTP. "
                    + "It touches no database and no use case, so it never reports on dependency health.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The process is up and serving HTTP.")
    })
    public String health() {
        return "OK";
    }
}
