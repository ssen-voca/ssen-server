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

## 앱

클라이언트는 별도 저장소에 있습니다 → [ssen-app](https://github.com/ssen-voca/ssen-app)

## 기여 방법

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](./CONTRIBUTING.md)를 반드시 읽고 따라주세요.
`main`, `develop`에는 직접 push할 수 없으며 모든 작업은 이슈 → 브랜치 → PR 순서로 진행합니다.
