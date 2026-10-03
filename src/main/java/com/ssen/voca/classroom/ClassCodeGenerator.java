package com.ssen.voca.classroom;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 참여코드 생성기. 0/O, 1/I/L처럼 헷갈리는 글자를 뺀 31자에서 6자리를 뽑는다. */
@Component
public class ClassCodeGenerator {

	static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	static final int LENGTH = 6;

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}
}
