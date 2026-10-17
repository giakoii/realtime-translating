package app.realtimetranslator;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.MessageEntity;
import app.realtimetranslator.session.entity.SummaryEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import app.realtimetranslator.session.repository.SummaryRepository;
import app.realtimetranslator.testsupport.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SummaryRepository summaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearDatabase() {
        meetingRepository.deleteAll();
    }

    @Test
    void flywayV1AppliedAndJsonbActionItemsRemainAnArray() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class);
        assertThat(migrationCount).isEqualTo(1);

        MeetingEntity meeting = saveMeeting("jsonb-session");
        SummaryEntity summary = new SummaryEntity();
        summary.setMeetingId(meeting.getId());
        summary.setTriggerType("manual");
        summary.setContent("Summary");
        summary.setActionItems("[{\"who\":\"Lan\",\"what\":\"Review\",\"deadline\":null}]");
        summary.setMessagesCount(1);

        SummaryEntity saved = summaryRepository.saveAndFlush(summary);
        entityManager.clear();

        SummaryEntity reloaded = summaryRepository.findById(saved.getId()).orElseThrow();
        String jsonType = jdbcTemplate.queryForObject(
                "select jsonb_typeof(action_items) from summaries where id = ?",
                String.class,
                saved.getId());

        assertThat(objectMapper.readTree(reloaded.getActionItems()))
                .isEqualTo(objectMapper.readTree(summary.getActionItems()));
        assertThat(jsonType).isEqualTo("array");
    }

    @Test
    void savesAndReadsTwoHundredFiftyMessagesInSequence() {
        MeetingEntity meeting = saveMeeting("batch-250-session");
        Instant baseTime = Instant.parse("2026-09-17T14:30:22Z");
        List<MessageEntity> messages = IntStream.range(0, 250)
                .mapToObj(sequence -> message(meeting, sequence, baseTime.plusMillis(sequence)))
                .toList();

        messageRepository.saveAllAndFlush(messages);

        List<MessageEntity> reloaded = messageRepository
                .findBySessionIdOrderBySequenceNumberAsc(meeting.getSessionId());
        assertThat(reloaded).hasSize(250);
        assertThat(reloaded).extracting(MessageEntity::getSequenceNumber)
                .containsExactlyElementsOf(IntStream.range(0, 250).mapToLong(value -> value).boxed().toList());
    }

    @Test
    void deletingMeetingCascadesToMessagesAndSummaries() {
        MeetingEntity meeting = saveMeeting("cascade-session");
        messageRepository.saveAndFlush(message(meeting, 0, Instant.parse("2026-09-17T14:30:22Z")));

        SummaryEntity summary = new SummaryEntity();
        summary.setMeetingId(meeting.getId());
        summary.setTriggerType("manual");
        summary.setContent("Summary");
        summary.setActionItems("[]");
        summary.setMessagesCount(1);
        summaryRepository.saveAndFlush(summary);

        meetingRepository.deleteById(meeting.getId());
        meetingRepository.flush();

        assertThat(messageRepository.count()).isZero();
        assertThat(summaryRepository.count()).isZero();
    }

    @Test
    void titleCanOnlyBeSetOnceByAtomicDatabaseUpdate() {
        MeetingEntity meeting = saveMeeting("title-session");

        int first = meetingRepository.updateTitleIfNull(meeting.getId(), "First title");
        int second = meetingRepository.updateTitleIfNull(meeting.getId(), "Second title");
        entityManager.clear();

        assertThat(first).isOne();
        assertThat(second).isZero();
        assertThat(meetingRepository.findById(meeting.getId()).orElseThrow().getTitle())
                .isEqualTo("First title");
    }

    private MeetingEntity saveMeeting(String sessionId) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setSessionId(sessionId);
        meeting.setSourceLang("ja");
        meeting.setTargetLang("vi");
        meeting.setStartedAt(Instant.parse("2026-09-17T14:30:22Z"));
        return meetingRepository.saveAndFlush(meeting);
    }

    private MessageEntity message(MeetingEntity meeting, long sequence, Instant spokenAt) {
        MessageEntity message = new MessageEntity();
        message.setMeetingId(meeting.getId());
        message.setSessionId(meeting.getSessionId());
        message.setSequenceNumber(sequence);
        message.setOriginalText("original-" + sequence);
        message.setTranslatedText("translated-" + sequence);
        message.setSourceLang("ja");
        message.setTargetLang("vi");
        message.setSpokenAt(spokenAt);
        return message;
    }
}
