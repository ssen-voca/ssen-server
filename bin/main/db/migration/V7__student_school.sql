-- 학생 식별을 참여코드(수업) 대신 학교 + 이름 + 뒤 4자리로 바꾼다. 학생은 수업에 속하지 않아도 된다.
ALTER TABLE app_user ADD COLUMN school VARCHAR(100);
ALTER TABLE app_user ADD COLUMN school_key VARCHAR(100);
CREATE INDEX idx_app_user_school_name ON app_user (school_key, name_key);
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_student_classroom;
-- NOT VALID: 학교가 없던 기존(개발용) 학생 행은 검사하지 않고, 새로 쓰는 행부터 검사한다.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_student_school
    CHECK (role <> 'STUDENT' OR school_key IS NOT NULL) NOT VALID;
