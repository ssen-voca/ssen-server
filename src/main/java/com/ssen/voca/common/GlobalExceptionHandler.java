package com.ssen.voca.common;

import com.ssen.voca.auth.InvalidCredentialsException;
import com.ssen.voca.auth.InvalidTokenException;
import com.ssen.voca.auth.StudentAlreadyExistsException;
import com.ssen.voca.auth.TooManyAttemptsException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	// 검증 오류를 필드 순서대로 안정적으로 내려주기 위한 순서 (name → phoneLast4).
	private static final List<String> FIELD_ORDER = List.of("name", "phoneLast4");

	@ExceptionHandler(StudentAlreadyExistsException.class)
	public ResponseEntity<Map<String, String>> handleAlreadyExists(StudentAlreadyExistsException e) {
		return message(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
	public ResponseEntity<Map<String, String>> handleUnauthorized(RuntimeException e) {
		return message(HttpStatus.UNAUTHORIZED, e.getMessage());
	}

	@ExceptionHandler(TooManyAttemptsException.class)
	public ResponseEntity<Map<String, String>> handleTooManyAttempts(TooManyAttemptsException e) {
		return message(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
		String text = e.getBindingResult().getFieldErrors().stream()
				.min(Comparator.comparingInt(error -> FIELD_ORDER.indexOf(error.getField())))
				.map(error -> error.getDefaultMessage())
				.orElse("요청 형식이 올바르지 않아요.");
		return message(HttpStatus.BAD_REQUEST, text);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
		return message(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요.");
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String text) {
		return ResponseEntity.status(status).body(Map.of("message", text));
	}
}
