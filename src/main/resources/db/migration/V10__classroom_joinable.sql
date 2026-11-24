-- 교사가 수업의 학생 참여를 닫고 열 수 있다(#34). 기존 수업은 모두 열려 있다.
ALTER TABLE classroom ADD COLUMN joinable BOOLEAN NOT NULL DEFAULT TRUE;
