package com.ssen.voca.wordbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WordbookController {
 private final JdbcTemplate db;
 private final ObjectMapper json;
 public WordbookController(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
 private long user(Authentication auth) {
  long uid=Long.parseLong(auth.getName());
  if(!db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=?)",Boolean.class,uid)) throw new com.ssen.voca.auth.InvalidTokenException("존재하지 않는 사용자입니다.");
  return uid;
 }
 // A word is visible only to its private owner or members of its assigned classroom.
 private static final String VISIBLE = "(w.owner_id = ? OR EXISTS (SELECT 1 FROM lesson_word lw JOIN lesson l ON l.id=lw.lesson_id JOIN app_user u ON u.classroom_id=l.classroom_id WHERE lw.word_id=w.id AND u.id=?))";
 @GetMapping("/api/me/words")
 public List<JsonNode> words(Authentication auth) {
  long uid=user(auth);
  return db.query("SELECT w.payload::text FROM word w WHERE " + VISIBLE + " ORDER BY w.created_at,w.id", (rs,n) -> {
   try { return json.readTree(rs.getString(1)); } catch(Exception e) { throw new IllegalStateException(e); }
  }, uid,uid);
 }
 @GetMapping("/api/me/bookmarks")
 public List<String> bookmarks(Authentication auth) {
  long uid=user(auth);
  return db.queryForList("SELECT b.word_id FROM bookmark b JOIN word w ON w.id=b.word_id WHERE b.user_id=? AND " + VISIBLE + " ORDER BY b.created_at,b.word_id",String.class,uid,uid,uid);
 }
 public record BookmarkRequest(String wordId) {}
 @PostMapping("/api/me/bookmarks")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional
 public void add(Authentication auth,@RequestBody BookmarkRequest request) {
  long uid=user(auth);
  int count=db.update("INSERT INTO bookmark(user_id,word_id) SELECT ?,w.id FROM word w WHERE w.id=? AND " + VISIBLE + " ON CONFLICT DO NOTHING",uid,request.wordId(),uid,uid);
  if(count==0 && !db.queryForObject("SELECT EXISTS(SELECT 1 FROM word w WHERE w.id=? AND " + VISIBLE + ")",Boolean.class,request.wordId(),uid,uid)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"단어를 찾을 수 없어요.");
 }
 @DeleteMapping("/api/me/bookmarks/{wordId}")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 public void remove(Authentication auth,@PathVariable String wordId) { db.update("DELETE FROM bookmark WHERE user_id=? AND word_id=?",user(auth),wordId); }
 private JsonNode validate(JsonNode w) {
  if(w == null || !w.isObject() || !w.path("id").isTextual() || w.path("id").asText().isBlank() || w.path("id").asText().length()>180 || !w.path("word").isTextual() || w.path("word").asText().isBlank() || !w.path("chapter").isTextual() || w.path("chapter").asText().isBlank() || w.path("chapter").asText().length()>100 || !w.path("meanings").isArray() || w.path("meanings").isEmpty() || !w.path("synonyms").isArray() || !w.path("details").isObject() || !w.path("details").path("example").isTextual() || !w.path("details").path("exampleKo").isTextual() || !w.path("details").path("exampleMeaning").isTextual() || w.toString().length()>50000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"단어 형식이 올바르지 않아요.");
  for(JsonNode meaning:w.path("meanings")) if(!meaning.isTextual() || meaning.asText().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"뜻을 확인해 주세요.");
  for(JsonNode synonym:w.path("synonyms")) if(!synonym.isTextual()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"동의어를 확인해 주세요.");
  validateExcelFields(w);
  return w;
 }
 // 교사 엑셀에서 오는 선택 값: 번호(0 이상 정수), 뜻별 품사(뜻과 같은 길이, 각 30자 이하), 예문에서 쓰인 형태(100자 이하). 없으면 건너뛴다.
 private static boolean absent(JsonNode n) { return n.isMissingNode() || n.isNull(); }
 private void validateExcelFields(JsonNode w) {
  JsonNode number=w.path("number");
  if(!absent(number) && !(number.isInt() && number.asInt()>=0)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"번호를 확인해 주세요.");
  JsonNode parts=w.path("partsOfSpeech");
  if(!absent(parts)) {
   if(!parts.isArray() || parts.size()!=w.path("meanings").size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"품사를 확인해 주세요.");
   for(JsonNode part:parts) if(!part.isTextual() || part.asText().length()>30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"품사를 확인해 주세요.");
  }
  JsonNode form=w.path("details").path("exampleForm");
  if(!absent(form) && (!form.isTextual() || form.asText().length()>100)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"예문 활용형을 확인해 주세요.");
 }
 @PostMapping("/api/me/words/import")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional
 public void importWords(Authentication auth,@RequestBody List<JsonNode> words) {
  if(words.size()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"한 번에 2000개까지 가져올 수 있어요.");
  long uid=user(auth);
  for(JsonNode raw:words) {
   var w=validate(raw).deepCopy(); String id="legacy:"+uid+":"+w.path("id").asText();
   ((com.fasterxml.jackson.databind.node.ObjectNode)w).put("id",id);
   db.update("INSERT INTO word(id,owner_id,payload) VALUES(?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",id,uid,w.toString());
  }
 }
 // 새 단어의 id는 서버가 정한다. 앱이 이후 북마크 등에 쓸 수 있게 저장된 id를 돌려준다.
 @PutMapping("/api/me/words/{wordId}")
 @Transactional
 public Map<String,String> savePrivate(Authentication auth,@PathVariable String wordId,@RequestBody JsonNode raw) {
  var w=validate(raw).deepCopy(); long uid=user(auth);
  var owners=db.queryForList("SELECT owner_id FROM word WHERE id=?",Long.class,wordId);
  if(!owners.isEmpty() && !Long.valueOf(uid).equals(owners.get(0))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"공통 단어는 수정할 수 없어요.");
  String id=owners.isEmpty() ? UUID.randomUUID().toString() : wordId;
  ((com.fasterxml.jackson.databind.node.ObjectNode)w).put("id",id);
  db.update("INSERT INTO word(id,owner_id,payload) VALUES(?,?,CAST(? AS jsonb)) ON CONFLICT(id) DO UPDATE SET payload=EXCLUDED.payload WHERE word.owner_id=EXCLUDED.owner_id",id,uid,w.toString());
  return Map.of("id",id);
 }
 @DeleteMapping("/api/me/words/{wordId}")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional
 public void deletePrivate(Authentication auth,@PathVariable String wordId) {
  long uid=user(auth);
  var owners=db.queryForList("SELECT owner_id FROM word WHERE id=?",Long.class,wordId);
  if(!owners.isEmpty() && !Long.valueOf(uid).equals(owners.get(0))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"공통 단어는 삭제할 수 없어요.");
  db.update("DELETE FROM word WHERE id=? AND owner_id=?",wordId,uid);
 }
 // 같은 수업·같은 차시에 같은 단어(대소문자·앞뒤 공백 무시)가 이미 있으면 새로 만들지 않고 내용을 고친다. id가 그대로라 학생의 북마크도 유지된다.
 // replace=true이면 올린 목록에 들어 있는 차시에서 올린 목록에 없는 단어를 지운다(그 단어의 학생 북마크도 같이 사라진다). 다른 차시는 건드리지 않는다.
 @PostMapping("/api/teacher/classrooms/{classroomId}/words")
 @ResponseStatus(HttpStatus.CREATED)
 @Transactional
 public Map<String,Integer> publish(Authentication auth,@PathVariable long classroomId,@RequestBody List<JsonNode> words,@RequestParam(required=false) String replace) {
  if(!db.queryForObject("SELECT EXISTS(SELECT 1 FROM classroom WHERE id=? AND teacher_id=?)",Boolean.class,classroomId,user(auth))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"수업을 찾을 수 없어요.");
  if(words.size()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"단어가 너무 많아요.");
  if(replace!=null && !replace.equals("true") && !replace.equals("false")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"replace는 true 또는 false여야 해요.");
  int created=0, updated=0, deleted=0;
  Map<String,Long> lessons=new java.util.LinkedHashMap<>(); java.util.Set<String> kept=new java.util.HashSet<>();
  for(JsonNode raw:words) {
   var w=validate(raw).deepCopy(); String chapter=w.path("chapter").asText();
   db.update("INSERT INTO lesson(classroom_id,chapter) VALUES(?,?) ON CONFLICT DO NOTHING",classroomId,chapter);
   Long lesson=db.queryForObject("SELECT id FROM lesson WHERE classroom_id=? AND chapter=?",Long.class,classroomId,chapter);
   lessons.put(chapter,lesson);
   var existing=db.queryForList("SELECT w.id FROM word w JOIN lesson_word lw ON lw.word_id=w.id WHERE lw.lesson_id=? AND lower(btrim(w.payload->>'word'))=lower(btrim(?)) ORDER BY w.created_at,w.id",String.class,lesson,w.path("word").asText());
   if(existing.isEmpty()) {
    String id=UUID.randomUUID().toString();
    ((com.fasterxml.jackson.databind.node.ObjectNode)w).put("id",id);
    // created_at 기본값(now())은 트랜잭션 시작 시각이라 한 번에 올린 단어가 모두 같아져 올린 순서를 잃는다. 실제 시각을 써서 엑셀 순서를 지킨다.
    db.update("INSERT INTO word(id,payload,created_at) VALUES(?,CAST(? AS jsonb),clock_timestamp())",id,w.toString());
    db.update("INSERT INTO lesson_word(lesson_id,word_id) VALUES(?,?)",lesson,id);
    kept.add(id); created++;
   } else {
    String id=existing.get(0);
    ((com.fasterxml.jackson.databind.node.ObjectNode)w).put("id",id);
    db.update("UPDATE word SET payload=CAST(? AS jsonb) WHERE id=?",w.toString(),id);
    kept.add(id); updated++;
   }
  }
  if("true".equals(replace)) {
   for(Long lesson:lessons.values()) for(String id:db.queryForList("SELECT word_id FROM lesson_word WHERE lesson_id=?",String.class,lesson)) if(!kept.contains(id)) deleted+=db.update("DELETE FROM word WHERE id=?",id);
   return Map.of("created",created,"updated",updated,"deleted",deleted);
  }
  return Map.of("created",created,"updated",updated);
 }
 private void ownClassroom(Authentication auth,long classroomId) {
  if(!db.queryForObject("SELECT EXISTS(SELECT 1 FROM classroom WHERE id=? AND teacher_id=?)",Boolean.class,classroomId,user(auth))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"수업을 찾을 수 없어요.");
 }
 // lesson 생성 순 → 번호(없으면 뒤) → 만든 순.
 @GetMapping("/api/teacher/classrooms/{classroomId}/words")
 public List<JsonNode> classroomWords(Authentication auth,@PathVariable long classroomId) {
  ownClassroom(auth,classroomId);
  return db.query("SELECT w.payload::text FROM word w JOIN lesson_word lw ON lw.word_id=w.id JOIN lesson l ON l.id=lw.lesson_id WHERE l.classroom_id=? ORDER BY l.id,CASE WHEN jsonb_typeof(w.payload->'number')='number' THEN (w.payload->>'number')::int END NULLS LAST,w.created_at,w.id", (rs,n) -> {
   try { return json.readTree(rs.getString(1)); } catch(Exception e) { throw new IllegalStateException(e); }
  }, classroomId);
 }
 // 단어는 원래 차시에 남는다. payload의 chapter·id는 서버가 정한 값으로 덮어쓴다.
 @PutMapping("/api/teacher/classrooms/{classroomId}/words/{wordId}")
 @Transactional
 public JsonNode updateClassroomWord(Authentication auth,@PathVariable long classroomId,@PathVariable String wordId,@RequestBody JsonNode raw) {
  ownClassroom(auth,classroomId);
  var found=db.queryForList("SELECT l.id,l.chapter FROM lesson_word lw JOIN lesson l ON l.id=lw.lesson_id WHERE l.classroom_id=? AND lw.word_id=?",classroomId,wordId);
  if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"단어를 찾을 수 없어요.");
  var w=(com.fasterxml.jackson.databind.node.ObjectNode)validate(raw).deepCopy();
  w.put("id",wordId).put("chapter",(String)found.get(0).get("chapter"));
  if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM word w JOIN lesson_word lw ON lw.word_id=w.id WHERE lw.lesson_id=? AND w.id<>? AND lower(btrim(w.payload->>'word'))=lower(btrim(?)))",Boolean.class,found.get(0).get("id"),wordId,w.path("word").asText())) throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 단어가 이미 있어요.");
  db.update("UPDATE word SET payload=CAST(? AS jsonb) WHERE id=?",w.toString(),wordId);
  return w;
 }
 // word 행을 지우면 북마크도 지워진다. 마지막 단어였다면 빈 차시도 지운다.
 @DeleteMapping("/api/teacher/classrooms/{classroomId}/words/{wordId}")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional
 public void deleteClassroomWord(Authentication auth,@PathVariable long classroomId,@PathVariable String wordId) {
  ownClassroom(auth,classroomId);
  var lessons=db.queryForList("SELECT l.id FROM lesson_word lw JOIN lesson l ON l.id=lw.lesson_id WHERE l.classroom_id=? AND lw.word_id=?",Long.class,classroomId,wordId);
  if(lessons.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"단어를 찾을 수 없어요.");
  db.update("DELETE FROM word WHERE id=?",wordId);
  db.update("DELETE FROM lesson WHERE id=? AND NOT EXISTS(SELECT 1 FROM lesson_word WHERE lesson_id=?)",lessons.get(0),lessons.get(0));
 }
 // 차시에 매달린 단어를 모두 지우고 차시도 지운다. 100자를 넘는 차시 이름은 어차피 없으므로 404다.
 @DeleteMapping("/api/teacher/classrooms/{classroomId}/chapters/{chapter}")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional
 public void deleteChapter(Authentication auth,@PathVariable long classroomId,@PathVariable String chapter) {
  ownClassroom(auth,classroomId);
  var lessons=db.queryForList("SELECT id FROM lesson WHERE classroom_id=? AND chapter=?",Long.class,classroomId,chapter);
  if(lessons.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"차시를 찾을 수 없어요.");
  db.update("DELETE FROM word WHERE id IN (SELECT word_id FROM lesson_word WHERE lesson_id=?)",lessons.get(0));
  db.update("DELETE FROM lesson WHERE id=?",lessons.get(0));
 }
}
