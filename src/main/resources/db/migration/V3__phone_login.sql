-- 이름 + 휴대폰 뒤 4자리 로그인으로 전환: 이메일 제거, PIN 해시, 정규화된 이름 키 추가.
ALTER TABLE app_user DROP COLUMN email;
ALTER TABLE app_user RENAME COLUMN password_hash TO pin_hash;
ALTER TABLE app_user ADD COLUMN name_key VARCHAR(50);
UPDATE app_user SET name_key = lower(trim(name));
ALTER TABLE app_user ALTER COLUMN name_key SET NOT NULL;
CREATE INDEX idx_app_user_name_key ON app_user (name_key);
