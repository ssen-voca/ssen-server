package com.ssen.voca.common;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);
	private static final int DB_CHECK_TIMEOUT_SECONDS = 2;

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** 서버가 살아 있는지만 본다. Railway 배포·재시작 판단(healthcheckPath)이 쓰므로 DB를 보지 않는다. */
	@GetMapping
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}

	/** DB 연결까지 본다. 외부 가동 모니터링용이고, 실패하면 503이다. 응답에는 내부 정보를 담지 않는다. */
	@GetMapping("/db")
	public ResponseEntity<Map<String, String>> db() {
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(DB_CHECK_TIMEOUT_SECONDS)) {
				return ResponseEntity.ok(Map.of("status", "UP"));
			}
		} catch (Exception e) {
			// 원인은 서버 로그에만 남긴다.
			log.warn("DB health check failed", e);
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
	}
}
