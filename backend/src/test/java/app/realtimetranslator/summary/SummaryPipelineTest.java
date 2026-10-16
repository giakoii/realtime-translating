package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.session.SessionLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummaryPipelineTest {

    private final GptClient gptClient = mock(GptClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SessionLifecycleService lifecycle = mock(SessionLifecycleService.class);
    private SummaryPipeline pipeline;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.summary()).thenReturn(new AppProperties.Summary(
                "gpt-4o", .5, 2000, 30, 1, 3, 50, 200, 1000));
        when(lifecycle.runIfOpen(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        pipeline = new SummaryPipeline(gptClient, new SummaryPromptBuilder(),
                JsonMapper.builder().build(), publisher, properties, lifecycle);
    }

    @Test
    void trimsMoreThanOneThousandEntriesToFiftyHeadAndTwoHundredTail() {
        List<ScriptEntry> entries = LongStream.range(0, 1001)
                .mapToObj(SummaryPromptBuilderTest::entry).toList();
        when(gptClient.summarize(anyString(), anyString()))
                .thenReturn("{\"content\":\"ok\",\"actionItems\":[]}");

        pipeline.execute("session01", entries, "manual");

        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(gptClient).summarize(anyString(), transcript.capture());
        assertTrue(transcript.getValue().contains("original-0"));
        assertTrue(transcript.getValue().contains("original-49"));
        assertFalse(transcript.getValue().contains("original-50\n"));
        assertTrue(transcript.getValue().contains("original-801"));
        assertTrue(transcript.getValue().contains("original-1000"));
        verify(publisher).publishEvent(argThat(event ->
                event instanceof SummaryGenerated generated && generated.getMessagesCount() == 1001));
    }

    @Test
    void fallsBackToRawContentWhenJsonIsMalformed() {
        when(gptClient.summarize(anyString(), anyString())).thenReturn("raw markdown");

        SummaryResult result = pipeline.execute(
                "session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual");

        assertEquals("raw markdown", result.content());
        assertNull(result.actionItems());
    }

    @Test
    void rejectsBlankProviderContentWithoutPublishing() {
        when(gptClient.summarize(anyString(), anyString())).thenReturn("  \n");

        assertThrows(IllegalStateException.class, () -> pipeline.execute(
                "session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual"));

        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsValidJsonWithBlankContentWithoutPublishing() {
        when(gptClient.summarize(anyString(), anyString()))
                .thenReturn("{\"content\":\"\",\"actionItems\":[]}");

        assertThrows(IllegalStateException.class, () -> pipeline.execute(
                "session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual"));

        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsNullActionItemWithoutPublishing() {
        when(gptClient.summarize(anyString(), anyString()))
                .thenReturn("{\"content\":\"ok\",\"actionItems\":[null]}");

        assertThrows(IllegalStateException.class, () -> pipeline.execute(
                "session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual"));

        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsActionItemWithBlankWhatWithoutPublishing() {
        when(gptClient.summarize(anyString(), anyString())).thenReturn(
                "{\"content\":\"ok\",\"actionItems\":[{\"who\":null,\"what\":\" \",\"deadline\":null}]}");

        assertThrows(IllegalStateException.class, () -> pipeline.execute(
                "session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual"));

        verifyNoInteractions(publisher);
    }

    @Test
    void doesNotPublishWhenSessionClosesWhileProviderIsRunning() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        AtomicBoolean open = new AtomicBoolean(true);
        when(gptClient.summarize(anyString(), anyString())).thenAnswer(invocation -> {
            providerStarted.countDown();
            assertTrue(providerRelease.await(5, TimeUnit.SECONDS));
            return "{\"content\":\"late\",\"actionItems\":[]}";
        });
        when(lifecycle.runIfOpen(eq("session01"), any(Runnable.class))).thenAnswer(invocation -> {
            if (!open.get()) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        CompletableFuture<SummaryResult> execution = CompletableFuture.supplyAsync(() ->
                pipeline.execute("session01", List.of(SummaryPromptBuilderTest.entry(1)), "manual"));
        assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
        open.set(false);
        providerRelease.countDown();
        execution.get(5, TimeUnit.SECONDS);

        verifyNoInteractions(publisher);
    }
}
