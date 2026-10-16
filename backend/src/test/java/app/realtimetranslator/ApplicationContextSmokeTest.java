package app.realtimetranslator;

import app.realtimetranslator.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationContextSmokeTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsWithFlywayManagedPostgres() {
        assertThat(applicationContext).isNotNull();
    }
}
