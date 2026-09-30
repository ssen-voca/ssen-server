# ssen-server

쎈슬기 단어장 — API 서버 (Spring Boot + PostgreSQL)

차시별 단어장, 학습 기록, 복습 스케줄, 조교용 엑셀 업로드를 담당하는 백엔드입니다.

## 기술 스택

| 영역 | 선택 |
| --- | --- |
| 프레임워크 | Spring Boot 3 (Java 21) |
| DB | PostgreSQL 16 |
| ORM | Spring Data JPA |
| 마이그레이션 | Flyway |
| 인증 | JWT (Access + Refresh) |
| 엑셀 파싱 | Apache POI |

## 주요 도메인

| 테이블 | 설명 |
| --- | --- |
| `lesson` | 차시 (수업 회차) |
| `word` / `sense` | 표제어와 의미 |
| `sense_relation` | 동의어 · 반의어 · 유의어 |
| `lesson_word` | 차시별 단어장 — 조교 엑셀 업로드의 결과 |
| `app_user` | 사용자 |
| `bookmark` | 나만의 단어장 |
| `study_record` | 학습 이력 + 복습 스케줄 |

## 로컬 실행

```bash
# PostgreSQL 실행 후
./gradlew bootRun
```

`src/main/resources/application-local.yml` 에 로컬 DB 접속 정보를 둡니다. (이 파일은 git에 올라가지 않습니다)

## 인증 API

학생은 **이름 + 휴대폰 번호 뒤 4자리**로 가입·로그인합니다. (이메일·비밀번호 없음)
휴대폰 뒤 4자리는 경우의 수가 1만 개뿐인 약한 자격 증명이므로 서버가 시도 횟수를 제한합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 가입 `{name, phoneLast4}` → 201 `{accessToken, refreshToken}`. 같은 이름 + 같은 번호는 409 |
| POST | `/api/auth/login` | 로그인 `{name, phoneLast4}` → 200 `{accessToken, refreshToken}`. 불일치는 401 |
| POST | `/api/auth/refresh` | `{refreshToken}` → `{accessToken}` |
| GET | `/api/users/me` | 내 정보 `{id, name, classCode, role}` (액세스 토큰 필요) |
| PATCH | `/api/users/me/class-code` | 참여 코드 등록 `{classCode}` (액세스 토큰 필요) |

- 이름은 앞뒤 공백 제거 · 연속 공백 1칸 · 소문자로 정규화해 같은 학생인지 판단합니다. 같은 이름의 다른 학생은 번호로 구분합니다.
- 번호는 BCrypt 해시로만 저장하며 응답·로그에 남기지 않습니다.
- 시도 제한: 이름 기준으로 가입 호출과 로그인 시도를 합쳐 5분 안에 5회가 되면 다음 시도는 429입니다. 시도는 처리 전에 먼저 차감되고, 로그인에 성공하면 그 1회만 돌려받습니다(카운터를 통째로 비우지 않습니다). (서버 1대 기준 메모리 방식 — `app.auth.max-attempts`, `app.auth.attempt-window-millis`)
- 앱이 직접 내는 오류(가입 중복 409, 로그인 실패 401, 시도 초과 429, 검증 실패·본문 형식 오류 400)는 `{"message": "..."}` 형태이고, 검증 실패는 첫 번째 오류 메시지를 담습니다. 프레임워크가 내는 오류(CORS 거절 403 "Invalid CORS request", 토큰 없는 요청의 401, 405·415 등)는 Spring 기본 응답 본문을 씁니다.
- 이름은 눈에 보이지 않는 문자(폭 없는 공백 등)를 제거한 뒤 비어 있으면 400입니다.
- 액세스 토큰 30분 · 리프레시 토큰 14일 (JWT, 무상태).

### CORS

`app.cors.allowed-origins`(쉼표 구분)에 등록한 출처만 `/api/**`를 브라우저에서 호출할 수 있습니다.
`local` 프로필은 `http://localhost:8081`, `:8099`, `:19006`을 허용하고, 그 외에는 기본적으로 아무것도 허용하지 않습니다.
운영에서는 환경 변수 `CORS_ALLOWED_ORIGINS`로 지정합니다. 허용되지 않은 출처의 요청(preflight 포함)은 403으로 거절됩니다.

## 앱

클라이언트는 별도 저장소에 있습니다 → [ssen-app](https://github.com/ssen-voca/ssen-app)

## 기여 방법

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](./CONTRIBUTING.md)를 반드시 읽고 따라주세요.
`main`, `develop`에는 직접 push할 수 없으며 모든 작업은 이슈 → 브랜치 → PR 순서로 진행합니다.
