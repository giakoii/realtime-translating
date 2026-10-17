package app.realtimetranslator.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "summaryExecutor")
    public ThreadPoolTaskExecutor summaryExecutor() {
        return taskExecutor("summary-", 2);
    }

    @Bean(name = "persistExecutor")
    public ThreadPoolTaskExecutor persistExecutor() {
        return taskExecutor("persist-", 2);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    private ThreadPoolTaskExecutor taskExecutor(String prefix, int poolSize) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
}
