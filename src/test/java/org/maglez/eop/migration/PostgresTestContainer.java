package org.maglez.eop.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 17 container, started once for the whole integration-test JVM.
 *
 * <p>The image is pinned to the same tag {@code compose.app.yml} runs in production
 * ({@code postgres:17-alpine}). Testing against a different minor or a different base image would
 * reintroduce, in smaller form, exactly the gap EOP-164 exists to close: a schema verified on one
 * engine and deployed on another.
 *
 * <p>Deliberately a static singleton rather than a JUnit {@code @Container} field.
 * {@code @Testcontainers} plus {@code @Container static} starts and stops one container per test
 * class, which for this suite would mean paying container startup several times over. Failsafe runs
 * with {@code forkCount=1} and {@code reuseForks=true} by default, so a single static container is
 * shared by every integration test in the run and startup is amortised across all of them. Nothing
 * stops it: the JVM exiting tears the container down, and Testcontainers' own Ryuk sidecar reaps it
 * if the JVM dies without unwinding.
 *
 * <p>Because the container is shared, test classes must not share a <em>database</em>. Liquibase
 * migration tests apply, roll back and re-apply the entire changelog, so two classes pointed at one
 * database would see each other's schema depending on execution order. {@link #freshDatabase(String)}
 * hands each class its own database inside the shared container, which is the cheap half of the
 * isolation (a {@code CREATE DATABASE} costs milliseconds; a container start costs seconds).
 *
 * <p>The container's <em>default</em> database is left untouched by the raw-Liquibase tests so that
 * the Spring-context test, which reaches the container through {@code @ServiceConnection} and
 * therefore cannot choose its own database name, has one nobody else migrates.
 */
final class PostgresTestContainer {

    /**
     * Pinned to match the {@code POSTGRES_IMAGE} default in {@code compose.app.yml}.
     */
    private static final String IMAGE = "postgres:17-alpine";

    private static final PostgreSQLContainer CONTAINER = startSingleton();

    private PostgresTestContainer() {
        throw new AssertionError("Static holder; not instantiable.");
    }

    private static PostgreSQLContainer startSingleton() {
        final PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
        container.start();
        return container;
    }

    /**
     * The shared container, started on first access.
     *
     * @return the running PostgreSQL 17 container
     */
    static PostgreSQLContainer container() {
        return CONTAINER;
    }

    /**
     * Drops and recreates a named database in the shared container, then connects to it.
     *
     * <p>Dropping first rather than only on teardown makes a test independent of whether the
     * previous run unwound cleanly. {@code WITH (FORCE)} terminates any connection still holding
     * the database open, which a failed test can leave behind; without it the {@code DROP} fails
     * with "database is being accessed by other users" and every subsequent run of the same class
     * inherits the previous run's schema.
     *
     * <p>Neither statement can run inside a transaction. The returned {@link Connection} is left in
     * the JDBC default of auto-commit, and callers that hand it to Liquibase must restore that
     * afterwards -- Liquibase turns auto-commit off and does not turn it back on, and on PostgreSQL,
     * where DDL is transactional, an uncommitted migration is invisible to metadata queries.
     *
     * @param databaseName lower-case database name, unique to the calling test class
     * @return a connection to the freshly created, empty database
     * @throws SQLException if the database cannot be recreated or connected to
     */
    static Connection freshDatabase(final String databaseName) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + databaseName);
        }
        return DriverManager.getConnection(
                jdbcUrlFor(databaseName), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /**
     * Builds a JDBC URL for a database other than the container's default.
     *
     * <p>Built from host and mapped port rather than by string-substituting
     * {@link PostgreSQLContainer#getJdbcUrl()}, whose query parameters would make that substitution
     * fragile.
     *
     * @param databaseName the database to address
     * @return a JDBC URL for that database in the shared container
     */
    private static String jdbcUrlFor(final String databaseName) {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":"
                + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
    }
}
