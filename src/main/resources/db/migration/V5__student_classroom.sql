-- 조각 2: 학생이 속한 수업. CHECK 제약과 class_code 제거는 가입·로그인이 바뀐 뒤(V6)에 한다.
ALTER TABLE app_user ADD COLUMN classroom_id BIGINT REFERENCES classroom (id);
CREATE INDEX idx_app_user_classroom_name ON app_user (classroom_id, name_key);
