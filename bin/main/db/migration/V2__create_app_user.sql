CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    class_code    VARCHAR(50),
    role          VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
