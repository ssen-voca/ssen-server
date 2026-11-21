package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherAccountsTest {

	@Test
	void blankOrNullGivesNoEntries() {
		assertThat(TeacherAccounts.parse(null)).isEmpty();
		assertThat(TeacherAccounts.parse("")).isEmpty();
		assertThat(TeacherAccounts.parse(" \n ; \n")).isEmpty();
	}

	@Test
	void parsesSemicolonSeparatedEntries() {
		List<TeacherAccounts.Entry> entries = TeacherAccounts.parse("가학원|김선생|1234;나학원|박선생|5678");

		assertThat(entries).extracting(TeacherAccounts.Entry::school).containsExactly("가학원", "나학원");
		assertThat(entries).extracting(TeacherAccounts.Entry::name).containsExactly("김선생", "박선생");
		assertThat(entries).extracting(TeacherAccounts.Entry::pin).containsExactly("1234", "5678");
	}

	@Test
	void parsesNewlineSeparatedEntriesAndSkipsBlankLines() {
		List<TeacherAccounts.Entry> entries = TeacherAccounts.parse("가학원|김선생|1234\r\n\n나학원|박선생|5678\n;");

		assertThat(entries).extracting(TeacherAccounts.Entry::name).containsExactly("김선생", "박선생");
	}

	@Test
	void trimsWhitespaceAroundFields() {
		List<TeacherAccounts.Entry> entries = TeacherAccounts.parse("  가학원 | 김선생 |  1234  ");

		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.school()).isEqualTo("가학원");
			assertThat(entry.name()).isEqualTo("김선생");
			assertThat(entry.pin()).isEqualTo("1234");
		});
	}

	@Test
	void wrongFieldCountNamesTheEntryNumber() {
		assertThatThrownBy(() -> TeacherAccounts.parse("가학원|김선생|1234;나학원|박선생"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("2번");
		assertThatThrownBy(() -> TeacherAccounts.parse("가학원|김선생|1234|9999"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("1번");
	}

	@Test
	void blankOrTooLongSchoolOrNameIsRejected() {
		assertThatThrownBy(() -> TeacherAccounts.parse("가학원|김선생|1234;  |박선생|5678"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("2번");
		assertThatThrownBy(() -> TeacherAccounts.parse("가학원||5678"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("1번");
		assertThatThrownBy(() -> TeacherAccounts.parse("가".repeat(101) + "|김선생|5678"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("1번");
		assertThatThrownBy(() -> TeacherAccounts.parse("가학원|" + "가".repeat(51) + "|5678"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("1번");
	}

	@Test
	void pinMustBeExactlyFourDigits() {
		for (String pin : List.of("123", "12345", "abcd", "12a4", "١٢٣٤", "")) {
			assertThatThrownBy(() -> TeacherAccounts.parse("가학원|김선생|" + pin))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("1번");
		}
	}

	@Test
	void duplicateSchoolAndNameIsRejectedEvenWithDifferentSpellingOrPin() {
		assertThatThrownBy(() -> TeacherAccounts.parse("가 학원|Kim 선생|1234;나학원|박선생|5678;가학원|kim  선생|9999"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("3번")
				.hasMessageContaining("1번");
	}

	@Test
	void sameNameInDifferentSchoolsIsFine() {
		assertThat(TeacherAccounts.parse("가학원|김선생|1234;나학원|김선생|1234")).hasSize(2);
	}

	@Test
	void messagesNeverEchoThePinAndEntryToStringHidesIt() {
		String pin = "7391";
		for (String bad : List.of(
				"가학원|김선생|" + pin + "|extra",
				"가학원|김선생|" + pin + "9",
				"가학원||" + pin,
				" |김선생|" + pin,
				"가학원|김선생|" + pin + ";가학원|김선생|" + pin)) {
			assertThatThrownBy(() -> TeacherAccounts.parse(bad))
					.isInstanceOf(IllegalStateException.class)
					.satisfies(e -> assertThat(e.getMessage()).doesNotContain(pin));
		}
		assertThat(TeacherAccounts.parse("가학원|김선생|" + pin).get(0).toString()).doesNotContain(pin);
	}
}
