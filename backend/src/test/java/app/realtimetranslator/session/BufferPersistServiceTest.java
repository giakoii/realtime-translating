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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        buffer.markFlushed(1);

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
        meeting.setStartedAt(Instant.now());
        meeting.setTitle("Existing title");
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));
        when(messageRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByMeetingId(MEETING_ID)).thenReturn(1);
        service.endSession(SESSION_ID);

        // Buffer removed from session repository
        assertTrue(sessionRepository.get(SESSION_ID).isEmpty());
        verify(meetingRepository).markEndedIfNull(eq(MEETING_ID), any(Instant.class));
        verify(meetingRepository).updateTotalMessages(MEETING_ID, 1);
        verify(meetingRepository, never()).save(meeting);
    }

    @Test
    void persistUnflushedMessages_failureKeepsWatermarkAndRetryPersistsAll() {
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));
        buffer.add(entry(2));
        MeetingEntity meeting = meeting();
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));
        when(messageRepository.saveAll(any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalStateException.class, () -> service.persistUnflushedMessages(SESSION_ID));
        assertEquals(2, buffer.unflushedCount());

        assertEquals(2, service.persistUnflushedMessages(SESSION_ID));
        assertEquals(0, buffer.unflushedCount());
        verify(messageRepository, times(2)).saveAll(argThat(rows -> ((List<?>) rows).size() == 2));
    }

    @Test
    void persistUnflushedMessages_concurrentAddRemainsForNextFlush() {
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting()));
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> {
            buffer.add(entry(2));
            return invocation.getArgument(0);
        });

        assertEquals(1, service.persistUnflushedMessages(SESSION_ID));

        assertEquals(List.of(2L), buffer.getUnflushedEntries().stream()
                .map(ScriptEntry::sequenceNumber).toList());
    }

    @Test
    void persistUnflushedMessages_twoConcurrentFlushesDoNotDuplicate() throws Exception {
        ScriptBuffer buffer = sessionRepository.getOrCreate(SESSION_ID);
        buffer.add(entry(1));
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting()));
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> {
            saveStarted.countDown();
            assertTrue(releaseSave.await(2, TimeUnit.SECONDS));
            return invocation.getArgument(0);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.persistUnflushedMessages(SESSION_ID));
            assertTrue(saveStarted.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.persistUnflushedMessages(SESSION_ID));
            releaseSave.countDown();

            assertEquals(1, first.get(2, TimeUnit.SECONDS));
            assertEquals(0, second.get(2, TimeUnit.SECONDS));
        }
        verify(messageRepository, times(1)).saveAll(any());
    }

    @Test
    void endSession_twiceUsesConditionalEndedAtUpdate() {
        sessionRepository.getOrCreate(SESSION_ID);
        MeetingEntity meeting = meeting();
        meeting.setTitle("Existing title");
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));
        when(messageRepository.countByMeetingId(MEETING_ID)).thenReturn(0);

        service.endSession(SESSION_ID);
        service.endSession(SESSION_ID);

        verify(meetingRepository, times(2)).markEndedIfNull(eq(MEETING_ID), any(Instant.class));
        verify(meetingRepository, times(2)).updateTotalMessages(MEETING_ID, 0);
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void endSession_doesNotOverwriteTitleGeneratedDuringClose() {
        sessionRepository.getOrCreate(SESSION_ID);
        MeetingEntity stale = meeting();
        MeetingEntity refreshed = meeting();
        refreshed.setTitle("Generated title");
        when(meetingRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(stale), Optional.of(refreshed));
        when(messageRepository.countByMeetingId(MEETING_ID)).thenReturn(1);

        service.endSession(SESSION_ID);

        verify(meetingRepository, never()).save(any());
        verify(meetingRepository, never()).updateTitleIfNull(any(), any());
    }

    @Test
    void getOrCreateMeeting_rejectsEndedMeeting() {
        MeetingEntity meeting = meeting();
        meeting.setEndedAt(Instant.now());
        when(meetingRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(meeting));

        assertThrows(SessionEndedException.class,
                () -> service.getOrCreateMeeting(SESSION_ID, "ja", "vi"));
        verify(meetingRepository, never()).save(any());
    }

    private MeetingEntity meeting() {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(MEETING_ID);
        meeting.setSessionId(SESSION_ID);
        meeting.setStartedAt(Instant.now());
        return meeting;
    }

    private ScriptEntry entry(long seq) {
        return ScriptEntry.create(
                SESSION_ID, "original-" + seq, "translated-" + seq,
                "ja", "vi", Instant.now(), seq
        );
    }
}
