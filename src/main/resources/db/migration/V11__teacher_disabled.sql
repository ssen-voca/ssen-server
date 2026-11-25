-- 서버 설정(TEACHER_ACCOUNTS)에서 빠진 교사를 지우지 않고 막아 두기 위한 표시(#30). 기존 계정은 모두 활성이다.
ALTER TABLE app_user ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;
