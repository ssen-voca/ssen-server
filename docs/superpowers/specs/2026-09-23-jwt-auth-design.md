# 계정·로그인 API (JWT) 설계

## 배경

기획서(`쎈슬기 단어장 / 기획안`) M-01: 이메일 가입 또는 특강 참여 코드 입력, 기기를 바꿔도 학습 기록 유지. 이번 스코프는 **학생 앱 로그인만** 다룬다. 조교용 관리자 계정(M-10)은 별도 이슈.

## 회원가입/참여코드 플로우

1. 회원가입: 이름 + 이메일 + 비밀번호. 이메일이 로그인 아이디(유일).
2. 참여코드(class_code)는 가입과 별도 단계 — 가입 직후 화면에서 1회 입력, 이후 `PATCH /api/users/me/class-code`로 등록.

## 데이터 모델 (Flyway V2)

```sql
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    class_code    VARCHAR(50),
    role          VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
```

## 엔드포인트

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | X | `{name, email, password}` → 가입 + 자동 로그인, `{accessToken, refreshToken}` |
| POST | `/api/auth/login` | X | `{email, password}` → `{accessToken, refreshToken}` |
| POST | `/api/auth/refresh` | X (바디의 refreshToken으로 검증) | 새 accessToken 발급. refreshToken 재발급 없음(로테이션 없음) |
| PATCH | `/api/users/me/class-code` | O | `{classCode}` → 참여코드 등록 |
| GET | `/api/users/me` | O | 내 정보 조회 |

## 인증 방식

- **완전 stateless.** DB에 토큰을 저장하지 않는다. (트레이드오프: 리프레시 토큰 유출·기기 분실 시 만료 전 강제 무효화 불가 — 6주 프로젝트·소규모 사용자 규모 감안해 리스크 수용)
- Access 30분 / Refresh 14일. 둘 다 JWT(HS256, 비밀키 `JWT_SECRET` 환경변수, 기존 `.env.example`에 이미 존재).
- Refresh 토큰은 `type: refresh` 클레임으로 access 토큰과 구분해 `/api/auth/refresh` 외에는 쓰이지 못하게 검증한다.
- 클라이언트는 `Authorization: Bearer <accessToken>` 헤더로 인증한다.
- 라이브러리: `jjwt` (io.jsonwebtoken) — 서명·검증 로직을 직접 구현하지 않는다.
- 비밀번호 해시: `BCryptPasswordEncoder`.
- Spring Security: 세션 없는 stateless 설정, CSRF 비활성화. `/api/auth/**`, `/api/health`는 인증 없이 허용, 나머지는 인증 필요. `OncePerRequestFilter`로 Bearer 토큰을 파싱해 `SecurityContext`에 인증 정보를 설정한다.

## 에러 처리

- 이메일 중복 가입 시도 → `409 Conflict`
- 로그인 실패(이메일 없음 또는 비밀번호 불일치) → `401`, 어느 쪽이 틀렸는지 구분하지 않는 메시지(계정 존재 여부 비노출)
- 토큰 없음/무효/만료 → `401`
- refresh 엔드포인트에 access 토큰을 넣거나 그 반대 → `401`

## 테스트

- happy path 통합 테스트: 회원가입 → 로그인 → `/api/users/me` 인증 접근 성공
- 실패 케이스: 잘못된 비밀번호로 로그인 시 `401`

## 범위 밖

- 조교/관리자 로그인 (M-10, 별도 이슈)
- 리프레시 토큰 로테이션/강제 무효화 (DB 추적 방식으로 바꿀 경우 별도 이슈)
- 비밀번호 재설정, 이메일 인증
