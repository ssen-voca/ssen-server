package com.ssen.voca.wordbook;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WordbookControllerTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate db;
 private String signup(String name) throws Exception {
  String body=json.writeValueAsString(java.util.Map.of("school",UUID.randomUUID().toString(),"name",name,"phoneLast4","1234"));
  return json.readTree(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
 }
 private final String words="[{\"id\":\"local-1\",\"word\":\"insight\",\"chapter\":\"Day 1\",\"meanings\":[\"통찰\"],\"synonyms\":[\"\"],\"details\":{\"example\":\"\",\"exampleKo\":\"\",\"exampleMeaning\":\"\"}}]";
 @Test void unauthenticatedIsRejected() throws Exception {
  mvc.perform(get("/api/me/bookmarks")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/me/bookmarks").contentType(MediaType.APPLICATION_JSON).content("{\"wordId\":\"x\"}")).andExpect(status().isUnauthorized());
 }
 @Test void importBookmarksIsolationAndIdempotency() throws Exception {
  String a="Bearer "+signup("학생하나"), b="Bearer "+signup("학생둘");
  for(int i=0;i<2;i++) mvc.perform(post("/api/me/words/import").header("Authorization",a).contentType(MediaType.APPLICATION_JSON).content(words)).andExpect(status().isNoContent());
  var catalog=json.readTree(mvc.perform(get("/api/me/words").header("Authorization",a)).andReturn().getResponse().getContentAsString());
  org.junit.jupiter.api.Assertions.assertEquals(1,catalog.size());
  String id=catalog.get(0).path("id").asText();
  String bookmark=json.writeValueAsString(java.util.Map.of("wordId",id));
  for(int i=0;i<2;i++) mvc.perform(post("/api/me/bookmarks").header("Authorization",a).contentType(MediaType.APPLICATION_JSON).content(bookmark)).andExpect(status().isNoContent());
  mvc.perform(get("/api/me/bookmarks").header("Authorization",a)).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(get("/api/me/bookmarks").header("Authorization",b)).andExpect(jsonPath("$.length()").value(0));
  mvc.perform(get("/api/me/words").header("Authorization",b)).andExpect(jsonPath("$.length()").value(0));
  mvc.perform(post("/api/me/bookmarks").header("Authorization",b).contentType(MediaType.APPLICATION_JSON).content(bookmark)).andExpect(status().isNotFound());
  mvc.perform(delete("/api/me/bookmarks/{id}",id).header("Authorization",b)).andExpect(status().isNoContent());
  mvc.perform(get("/api/me/bookmarks").header("Authorization",a)).andExpect(jsonPath("$.length()").value(1));
  for(int i=0;i<2;i++) mvc.perform(delete("/api/me/bookmarks/{id}",id).header("Authorization",a)).andExpect(status().isNoContent());
  mvc.perform(get("/api/me/words").header("Authorization",a)).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(post("/api/me/bookmarks").header("Authorization",a).contentType(MediaType.APPLICATION_JSON).content(bookmark));
  db.update("DELETE FROM word WHERE id=?",id);
  mvc.perform(get("/api/me/bookmarks").header("Authorization",a)).andExpect(jsonPath("$.length()").value(0));
 }
 @Test void privateWordWritesAreOwnerScoped() throws Exception {
  String a="Bearer "+signup("편집학생"),b="Bearer "+signup("다른학생");
  String payload=json.readTree(words).get(0).toString();
  mvc.perform(put("/api/me/words/new").header("Authorization",a).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isNoContent());
  String id=json.readTree(mvc.perform(get("/api/me/words").header("Authorization",a)).andReturn().getResponse().getContentAsString()).get(0).path("id").asText();
  mvc.perform(put("/api/me/words/{id}",id).header("Authorization",b).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
  mvc.perform(delete("/api/me/words/{id}",id).header("Authorization",b)).andExpect(status().isForbidden());
  mvc.perform(delete("/api/me/words/{id}",id).header("Authorization",a)).andExpect(status().isNoContent());
  mvc.perform(get("/api/me/words").header("Authorization",a)).andExpect(jsonPath("$.length()").value(0));
 }
 @Test void invalidImportIsAtomic() throws Exception {
  String token="Bearer "+signup("학생셋");
  mvc.perform(post("/api/me/words/import").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(words.replace("}]", "},{}]"))).andExpect(status().isBadRequest());
 }
 @Test void studentCannotPublish() throws Exception {
  mvc.perform(post("/api/teacher/classrooms/1/words").header("Authorization","Bearer "+signup("학생넷")).contentType(MediaType.APPLICATION_JSON).content(words)).andExpect(status().isForbidden());
 }
}
