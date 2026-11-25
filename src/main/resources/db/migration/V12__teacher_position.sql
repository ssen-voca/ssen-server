-- 교사 계정의 표시 구분(교사/조교). 권한은 같고(role은 TEACHER 그대로) 앱이 이름만 다르게 보여 준다(#29). 비어 있으면 교사.
ALTER TABLE app_user ADD COLUMN teacher_position VARCHAR(20);
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_teacher_position
    CHECK (teacher_position IS NULL OR (role = 'TEACHER' AND teacher_position IN ('TEACHER', 'ASSISTANT')));
