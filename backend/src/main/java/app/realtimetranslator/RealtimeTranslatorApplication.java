package app.realtimetranslator;

import app.realtimetranslator.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableAsync
@EnableScheduling
public class RealtimeTranslatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeTranslatorApplication.class, args);
    }
}
