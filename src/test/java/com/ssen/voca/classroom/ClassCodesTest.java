package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClassCodesTest {

	@Test
	void normalizeStripsAndUpperCases() {
		assertThat(ClassCodes.normalize("abc234")).isEqualTo("ABC234");
		assertThat(ClassCodes.normalize("  Abc234 \n")).isEqualTo("ABC234");
		assertThat(ClassCodes.normalize("　ABC234　")).isEqualTo("ABC234");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
			"", "ABC23", "ABC2345", "ABC 234", "I0O1L1", "ABC23!", "가나다라마바"})
	void normalizeReturnsNullForInvalidFormats(String raw) {
		assertThat(ClassCodes.normalize(raw)).isNull();
	}
}
