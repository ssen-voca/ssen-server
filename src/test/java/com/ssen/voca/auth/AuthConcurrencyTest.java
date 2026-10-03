package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 여러 스레드에서 요청해야 하므로 @Transactional을 쓰지 않는다 (없는 이름만 쓰고, 수업·교사 행은 @AfterEach에서 지운다).
@SpringBootTest
@AutoConfigureMockMvc
class AuthConcurrencyTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ClassroomFixture fixture;

	private Classroom classroom;

	@BeforeEach
	void newClassroom() {
		classroom = fixture.newClassroom();
	}

	@AfterEach
	void cleanUp() {
		fixture.deleteClassroomAndTeacher(classroom);
	}

	@Test
	void parallelWrongPinLoginsNeverGetMoreThanMaxPastTheLimiter() throws Exception {
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return mockMvc.perform(post("/api/auth/login")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"classCode\":\"" + classroom.getCode()
									+ "\",\"name\":\"동시성없는사람\",\"phoneLast4\":\"0000\"}"))
						.andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		int unauthorized = 0;
		int tooMany = 0;
		for (Future<Integer> future : futures) {
			int status = future.get();
			if (status == 401) {
				unauthorized++;
			} else if (status == 429) {
				tooMany++;
			}
		}
		pool.shutdown();

		assertThat(unauthorized).isEqualTo(5);
		assertThat(tooMany).isEqualTo(15);
	}
}
