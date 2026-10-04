package com.ssen.voca.classroom;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 참여코드 생성기. 0/O, 1/I/L처럼 헷갈리는 글자를 뺀 31자에서 6자리를 뽑는다. */
@Component
public class ClassCodeGenerator {

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		StringBuilder code = new StringBuilder(ClassCodes.LENGTH);
		for (int i = 0; i < ClassCodes.LENGTH; i++) {
			code.append(ClassCodes.ALPHABET.charAt(random.nextInt(ClassCodes.ALPHABET.length())));
		}
		return code.toString();
	}
}
