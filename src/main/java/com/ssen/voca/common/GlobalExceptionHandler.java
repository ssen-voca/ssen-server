package com.ssen.voca.common;

import com.ssen.voca.auth.EmailAlreadyExistsException;
import com.ssen.voca.auth.InvalidCredentialsException;
import com.ssen.voca.auth.InvalidTokenException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(EmailAlreadyExistsException.class)
	public ResponseEntity<Map<String, String>> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
	public ResponseEntity<Map<String, String>> handleUnauthorized(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
	}
}
