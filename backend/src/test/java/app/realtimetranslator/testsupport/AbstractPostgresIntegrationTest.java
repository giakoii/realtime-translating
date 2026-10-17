package app.realtimetranslator.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL 16 fixture for Spring integration tests.
 *
 * <p>Set {@code TEST_DATABASE_URL} to use an already-running, isolated PostgreSQL
 * database instead of Docker. {@code TEST_DATABASE_USERNAME} and
 * {@code TEST_DATABASE_PASSWORD} default to {@code postgres}. When neither an
 * external URL nor Docker is available, the test is visibly skipped.</p>
 */
@ActiveProfiles("test")
@ExtendWith(AbstractPostgresIntegrationTest.DatabaseAvailabilityCondition.class)
public abstract class AbstractPostgresIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("realtime_translator_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (hasExternalDatabase()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> environmentOrDefault(
                    "TEST_DATABASE_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> environmentOrDefault(
                    "TEST_DATABASE_PASSWORD", "postgres"));
            return;
        }

        startContainerOnce();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static synchronized void startContainerOnce() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    private static boolean hasExternalDatabase() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static final class DatabaseAvailabilityCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (hasExternalDatabase()) {
                return ConditionEvaluationResult.enabled("TEST_DATABASE_URL is configured");
            }
            try {
                if (DockerClientFactory.instance().isDockerAvailable()) {
                    return ConditionEvaluationResult.enabled("Docker is available");
                }
            } catch (RuntimeException unavailable) {
                // The disabled result below keeps the missing infrastructure visible.
            }
            return ConditionEvaluationResult.disabled(
                    "PostgreSQL integration test requires Docker or TEST_DATABASE_URL");
        }
    }
}
