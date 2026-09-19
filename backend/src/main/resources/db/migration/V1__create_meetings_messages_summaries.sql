-- =============================================
-- V1: BUF001 — Script Buffer & Storage tables
-- =============================================

-- Each meeting session maps 1:1 to a WebSocket connection lifecycle.
-- title is nullable because it gets back-filled from the first GPT-4o summary.
CREATE TABLE meetings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(64) UNIQUE NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    title           VARCHAR(255),
    source_lang     VARCHAR(8) NOT NULL,
    target_lang     VARCHAR(8) NOT NULL,
    total_messages  INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- DESC index because the most common query is "show recent meetings".
CREATE INDEX idx_meetings_started_at ON meetings(started_at DESC);

-- Each message is one translated utterance batch-inserted from ScriptBuffer.
-- session_id is denormalized from meetings to enable direct lookups without JOIN.
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id      UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    session_id      VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    original_text   TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    source_lang     VARCHAR(8) NOT NULL,
    target_lang     VARCHAR(8) NOT NULL,
    spoken_at       TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_meeting_id ON messages(meeting_id);
CREATE INDEX idx_messages_spoken_at  ON messages(spoken_at);
-- session_id index for direct lookup without going through meetings table.
CREATE INDEX idx_messages_session_id ON messages(session_id);

-- A meeting can have multiple summaries (auto-silence + manual triggers).
-- action_items is JSONB because the schema is defined by GPT-4o prompt output
-- and may evolve without requiring a migration.
CREATE TABLE summaries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id      UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    trigger_type    VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    action_items    JSONB,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    messages_count  INT NOT NULL
);

CREATE INDEX idx_summaries_meeting_id ON summaries(meeting_id);
