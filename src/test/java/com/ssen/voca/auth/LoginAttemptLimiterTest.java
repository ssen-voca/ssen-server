package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

	private final MutableClock clock = new MutableClock();
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock, 5, 300_000L);

	private void acquire(String key, int times) {
		for (int i = 0; i < times; i++) {
			limiter.tryAcquire(key);
		}
	}

	@Test
	void rejectsAfterMaxAttempts() {
		acquire("a", 5);

		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void rejectedAttemptIsNotCounted() {
		acquire("a", 5);
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);

		limiter.release("a");

		// 거절된 시도가 세어졌다면 한 번 돌려받아도 여전히 막혀 있을 것이다.
		assertThatCode(() -> limiter.tryAcquire("a")).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void otherKeysAreUnaffected() {
		acquire("a", 5);

		assertThatCode(() -> limiter.tryAcquire("b")).doesNotThrowAnyException();
	}

	@Test
	void windowIsFixedFromFirstAttemptAndResetsAfterExpiry() {
		acquire("a", 1);
		clock.advanceMillis(299_999);
		acquire("a", 4);
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);

		clock.advanceMillis(1);
		assertThatCode(() -> acquire("a", 5)).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void releaseRefundsExactlyOne() {
		acquire("a", 5);

		limiter.release("a");

		limiter.tryAcquire("a");
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void releaseNeverGoesBelowZero() {
		limiter.release("a");
		limiter.release("a");

		acquire("a", 5);
		assertThatThrownBy(() -> limiter.tryAcquire("a")).isInstanceOf(TooManyAttemptsException.class);
	}

	@Test
	void parallelAttemptsNeverExceedMax() throws Exception {
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				try {
					limiter.tryAcquire("a");
					allowed.incrementAndGet();
				} catch (TooManyAttemptsException e) {
					rejected.incrementAndGet();
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> future : futures) {
			future.get();
		}
		pool.shutdown();

		assertThat(allowed.get()).isEqualTo(5);
		assertThat(rejected.get()).isEqualTo(15);
	}
}
