package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.MeetingTitleGenerated;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TitleServiceTest {

    private final MeetingRepository repository = mock(MeetingRepository.class);
    private final GptClient gptClient = mock(GptClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private TitleService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.title()).thenReturn(new AppProperties.Title("gpt-4o", .3, 40, 8, 1, 12));
        service = new TitleService(repository, gptClient, publisher, properties);
    }

    @Test
    void cleansMarkdownQuotesLineBreaksAndLimitsLength() {
        assertEquals("A long meeti", service.clean("## \"A long meeting title\"\nignored"));
    }

    @Test
    void publishesOnlyWhenAtomicUpdateWins() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(UUID.randomUUID());
        when(repository.findBySessionId("session01")).thenReturn(Optional.of(meeting));
        when(gptClient.generateTitle("summary")).thenReturn("`Weekly sync`");
        when(repository.updateTitleIfNull(meeting.getId(), "Weekly sync")).thenReturn(1);

        service.generateIfAbsent("session01", "summary");

        verify(publisher).publishEvent(argThat(event ->
                event instanceof MeetingTitleGenerated generated
                        && generated.getTitle().equals("Weekly sync")));
    }

    @Test
    void skipsProviderWhenTitleAlreadyExists() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setTitle("Existing");
        when(repository.findBySessionId("session01")).thenReturn(Optional.of(meeting));

        service.generateIfAbsent("session01", "summary");

        verifyNoInteractions(gptClient, publisher);
    }
}
