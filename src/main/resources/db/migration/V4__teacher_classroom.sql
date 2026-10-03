-- 교사 계정과 수업(조각 1a). 비밀번호(교사)와 PIN(학생)을 같은 열에 담으므로 이름을 일반화한다.
ALTER TABLE app_user RENAME COLUMN pin_hash TO secret_hash;
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
CREATE UNIQUE INDEX uq_app_user_email ON app_user (email) WHERE email IS NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_teacher_email CHECK (role <> 'TEACHER' OR email IS NOT NULL);

CREATE TABLE classroom (
    id         BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT       NOT NULL REFERENCES app_user (id),
    name       VARCHAR(100) NOT NULL,
    code       VARCHAR(6)   NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_classroom_teacher ON classroom (teacher_id);
