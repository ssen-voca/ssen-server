-- 교사를 이메일 대신 학생과 같은 (학교, 이름, 번호)로 식별한다. 이메일 열과 옛 이메일 교사 행은 그대로 둔다.
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_teacher_email;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_teacher_identity
    CHECK (role <> 'TEACHER' OR email IS NOT NULL OR school_key IS NOT NULL);
