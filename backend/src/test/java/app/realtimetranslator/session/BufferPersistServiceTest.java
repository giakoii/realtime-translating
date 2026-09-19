package app.realtimetranslator.session;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BufferPersistServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private MessageRepository messageRepository;

    private SessionRepository sessionRepository;
    private BufferPersistService service;

    private static final String SESSION_ID = "test-session";
    private static final UUID MEETING_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = new SessionRepository();
        service = new BufferPersistService(sessionRepository, meetingRepository, messageRepository);
    }

    @Test
    void createMeeting_savesMeetingEntity() {
        MeetingEntity saved = new MeetingEntity();
        saved.setId(MEETING_ID);
        when(meetingRepository.save(any())).thenReturn(saved);

        UUID id = service.createMeeting(SESSION_ID, "ja", "vi");

        assertEquals(MEETING_ID, id);

        ArgumentCaptor<MeetingEntity> captor = ArgumentCaptor.forClass(MeetingEntity.class);
        verify(meetingRepository).save(captor.capture());
        MeetingEntity entity = captor.getValue();
        assertEquals(SESSION_ID, entity.getSessionId());
        assertEquals("ja", entity.getSourceLang());
        assertEquals("vi", entity.getTargetLang());
    }

    @Test
    void persistUnflushedMessages_whenNoBuffer_returnsZero() {
        int count = service.persistUnflushedMessages("no-such-session");

        assertEquals(0, count);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void persistUnflushedMessages_persistsAndMarksAsFlushed() {
        // Setup buffer with 3 entries
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));
        buffer.add(entry(2));
        buffer.add(entry(3));

        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(MEETING_ID);
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));
        when(messageRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.persistUnflushedMessages(SESSION_ID);

        assertEquals(3, count);
        assertEquals(0, buffer.unflushedCount()); // marked as flushed
        verify(messageRepository).saveAll(argThat(list -> ((List<?>) list).size() == 3));
    }

    @Test
    void persistUnflushedMessages_whenAllFlushed_returnsZero() {
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));
        buffer.markFlushed();

        int count = service.persistUnflushedMessages(SESSION_ID);

        assertEquals(0, count);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void endSession_flushesAndCleansUp() {
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));

        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(MEETING_ID);
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));
        when(messageRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByMeetingId(MEETING_ID)).thenReturn(1);
        when(meetingRepository.save(any())).thenReturn(meeting);

        service.endSession(SESSION_ID);

        // Buffer removed from session repository
        assertTrue(sessionRepository.get(SESSION_ID).isEmpty());
        // Meeting updated with ended_at and total_messages
        verify(meetingRepository).save(argThat(m -> m.getEndedAt() != null && m.getTotalMessages() == 1));
    }

    private ScriptEntry entry(long seq) {
        return ScriptEntry.create(
                SESSION_ID, "original-" + seq, "translated-" + seq,
                "ja", "vi", Instant.now(), seq
        );
    }
}
