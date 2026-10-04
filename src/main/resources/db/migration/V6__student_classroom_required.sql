-- 조각 2 마무리: 새 학생은 반드시 수업에 속하고, 참여 코드 문자열 열은 더 쓰지 않는다.
-- NOT VALID: 수업이 없던 기존(개발용) 학생 행은 검사하지 않고, 새로 쓰는 행부터 검사한다.
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_student_classroom
    CHECK (role <> 'STUDENT' OR classroom_id IS NOT NULL) NOT VALID;
ALTER TABLE app_user DROP COLUMN class_code;
