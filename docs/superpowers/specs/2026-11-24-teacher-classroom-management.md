# 교사의 수업 관리 (#34)

교사가 자기 수업을 고치고, 참여를 닫고 열고, 코드를 다시 발급하고, 학생을 보고 내보내고, 수업을 지운다. 모든 경로는 `/api/teacher/**`(TEACHER 전용)이고, 수업 소유는 `classroom.teacher_id`가 내 id인지로 확인한다. 남의 수업과 없는 수업은 같은 404 `수업을 찾을 수 없어요.`로 답한다.

## 응답 필드
수업 응답(목록·생성·수정·재발급)에 `joinable`(boolean)과 `studentCount`(그 수업에 속한 학생 수)를 더했다. 기존 `id, name, code, createdAt`은 그대로다.

## 엔드포인트
| 요청 | 동작 |
| --- | --- |
| `PATCH /api/teacher/classrooms/{id}` `{name?, joinable?}` | 200 + 수업. 둘 다 없으면 400. `name`은 생성과 같다(앞뒤 공백 제거 후 비어 있으면 안 됨, 100자 이하). |
| `POST /api/teacher/classrooms/{id}/code` | 200 + 수업(새 `code`). 생성과 같은 방식으로 코드를 뽑아 중복이면 다시 뽑는다. |
| `GET /api/teacher/classrooms/{id}/students` | 200 + `[{id, name, school}]`, 학교·이름 순. |
| `DELETE /api/teacher/classrooms/{id}/students/{studentId}` | 204. 이 수업의 학생이 아니면 404 `학생을 찾을 수 없어요.` |
| `DELETE /api/teacher/classrooms/{id}` | 204. |

## 참여 닫기·열기
`classroom.joinable BOOLEAN NOT NULL DEFAULT TRUE`(V10). 닫힌 수업의 코드로 학생이 참여하면 403 `지금은 이 수업에 참여할 수 없어요. 선생님께 문의해 주세요.`
- 시도 제한 차감은 돌려주지 않는다. 닫힌 코드를 반복해서 두드려도 제한(5회/5분)에 걸리므로 코드 추측 방어가 유지된다.
- 이미 그 수업에 속한 학생이 같은 코드로 다시 들어오는 것은 닫힌 수업이어도 200이다.
- 없는 코드 404와 성공 시 차감 환불은 그대로다. 다시 열면 바로 참여할 수 있다.

## 코드 재발급의 영향
옛 코드는 즉시 쓸 수 없다(없는 코드와 같은 404). 이미 들어온 학생의 소속(`classroom_id`)은 바뀌지 않고, `/api/users/me`의 `classroom.code`에는 새 코드가 보인다.

## 학생 목록과 내보내기
- 학생 목록은 **이름과 학교만** 내려준다(`id`는 내보내기 요청용). 번호 해시(`secret_hash`), `name_key`, `school_key`, 역할 등 내부 값은 응답에 없다.
- 내보내기는 그 학생의 `classroom_id`만 비운다. 계정·북마크·개인 단어는 그대로이고, 그 수업의 단어는 더 이상 보이지 않는다. 참여가 열려 있으면 코드로 다시 들어올 수 있다.

## 삭제의 영향
한 트랜잭션에서 순서대로 한다.
1. 소속 학생들의 `classroom_id`를 NULL로 만든다(학생 계정은 남는다).
2. 이 수업의 lesson에 매달린 `word` 행을 지운다. 교사 단어는 `owner_id`가 NULL이고 `lesson_word`로만 매달려 있어서, lesson을 지워도 `word` 행이 남기 때문이다. 이 단어의 북마크는 `ON DELETE CASCADE`로 같이 사라진다.
3. `classroom`을 지운다(`lesson`, `lesson_word`는 연쇄 삭제).

고아 `word` 행은 남지 않는다. 학생의 개인 단어(`owner_id`가 있음)와 다른 수업의 단어·북마크는 건드리지 않는다. 지운 수업의 코드로 참여하면 404다.

## 시도 제한
새 엔드포인트에는 시도 제한을 두지 않는다(교사 전용이고 코드 추측 경로가 아니다).
