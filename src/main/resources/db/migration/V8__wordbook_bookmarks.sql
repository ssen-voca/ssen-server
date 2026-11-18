CREATE TABLE lesson (
 id BIGSERIAL PRIMARY KEY,
 classroom_id BIGINT NOT NULL REFERENCES classroom(id) ON DELETE CASCADE,
 chapter VARCHAR(100) NOT NULL,
 UNIQUE(classroom_id, chapter)
);
CREATE TABLE word (
 id VARCHAR(255) PRIMARY KEY,
 owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE lesson_word (
 lesson_id BIGINT NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
 word_id VARCHAR(255) NOT NULL REFERENCES word(id) ON DELETE CASCADE,
 PRIMARY KEY(lesson_id, word_id),
 UNIQUE(word_id)
);
CREATE TABLE bookmark (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 word_id VARCHAR(255) NOT NULL REFERENCES word(id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, word_id)
);
CREATE INDEX idx_bookmark_word ON bookmark(word_id);
