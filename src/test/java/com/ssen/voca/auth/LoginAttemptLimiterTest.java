package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

	private final MutableClock clock = new MutableClock();
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock, 5, 300_000L);

	private void attempt(String key, int times) {
		for (int i = 0; i < times; i++) {
			limiter.checkAllowed(key);
			limiter.recordAttempt(key);
		}
	}

	@Test
	void rejectsAfterMaxAttempts() {
		attempt("a", 5);

		assertThatThrownBy(() -> limiter.checkAllowed("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherKeysAreUnaffected() {
		attempt("a", 5);

		assertThatCode(() -> limiter.checkAllowed("b")).doesNotThrowAnyException();
	}

	@Test
	void windowIsFixedFromFirstAttemptAndResetsAfterExpiry() {
		attempt("a", 1);
		clock.advanceMillis(299_999);
		attempt("a", 4);
		assertThatThrownBy(() -> limiter.checkAllowed("a")).isInstanceOf(TooManyAttemptsException.class);

		clock.advanceMillis(1);
		assertThatCode(() -> attempt("a", 5)).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.checkAllowed("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void clearResetsCounter() {
		attempt("a", 5);

		limiter.clear("a");

		assertThatCode(() -> limiter.checkAllowed("a")).doesNotThrowAnyException();
	}
}
