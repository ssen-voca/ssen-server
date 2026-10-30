package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class SchoolsTest {

	@Test
	void displayTrimsCollapsesSpacesAndDropsInvisibleCharacters() {
		assertThat(Schools.display("  동국대   부속\t고 ")).isEqualTo("동국대 부속 고");
		assertThat(Schools.display("서​울﻿고")).isEqualTo("서울고");
	}

	@Test
	void keyIgnoresSpacingAndCase() {
		assertThat(Schools.key(" 동국대 부속고 ")).isEqualTo(Schools.key("동국대부속고"));
		assertThat(Schools.key("DONGGUK High")).isEqualTo("donggukhigh");
		assertThat(Schools.key("Dongguk High")).isEqualTo("donggukhigh");
	}

	@Test
	void keyTreatsDecomposedAndComposedHangulAsTheSame() {
		String composed = "한국고";
		String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
		assertThat(decomposed).isNotEqualTo(composed);
		assertThat(Schools.key(decomposed)).isEqualTo(Schools.key(composed));
		assertThat(Schools.display(decomposed)).isEqualTo(composed);
	}

	@Test
	void blankOrInvisibleOnlyIsRejected() {
		assertThatThrownBy(() -> Schools.key("   ")).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.BLANK);
		assertThatThrownBy(() -> Schools.key("​⁠")).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.BLANK);
	}

	@Test
	void tooLongAfterNormalizationIsRejected() {
		assertThat(Schools.key("가".repeat(100))).hasSize(100);
		assertThatThrownBy(() -> Schools.key("가".repeat(101))).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.TOO_LONG);
		// 'İ'는 소문자화하면 두 글자(i + 결합점)가 되어 100자를 넘을 수 있다.
		// U+0344는 NFC에서 두 글자로 풀리고 사이 공백은 표시값에만 남아, 키는 100자인데 표시값은 149자가 된다.
		String expanding = "\u0344 ".repeat(50);
		assertThat(Schools.display(expanding)).hasSize(149);
		assertThatThrownBy(() -> Schools.key(expanding)).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.TOO_LONG);
		assertThatThrownBy(() -> Schools.key("İ".repeat(60))).isInstanceOf(InvalidSchoolException.class)
				.hasMessage(InvalidSchoolException.TOO_LONG);
	}
}
