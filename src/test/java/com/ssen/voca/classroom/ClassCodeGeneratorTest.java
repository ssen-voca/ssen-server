package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassCodeGeneratorTest {

	private final ClassCodeGenerator generator = new ClassCodeGenerator();

	@Test
	void codesAreSixCharsFromTheUnambiguousAlphabet() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			String code = generator.generate();
			assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
			seen.add(code);
		}
		// 31^6 가지에서 2000개를 뽑으면 거의 겹치지 않는다 (상수로 고정된 값이 아닌지 확인).
		assertThat(seen.size()).isGreaterThan(1990);
	}
}
