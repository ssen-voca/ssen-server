package com.ssen.voca.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class RequestSizeLimitFilterTest {

	@Test
	void allowsABodyUpToTheLimit() throws IOException {
		InputStream in = RequestSizeLimitFilter.limit(new ByteArrayInputStream(new byte[10]), 10);
		assertThat(in.readAllBytes()).hasSize(10);
	}

	@Test
	void failsOnceMoreThanTheLimitHasBeenRead() {
		InputStream in = RequestSizeLimitFilter.limit(new ByteArrayInputStream(new byte[11]), 10);
		assertThatThrownBy(in::readAllBytes).isInstanceOf(RequestSizeLimitFilter.TooLargeException.class);
	}

	@Test
	void countsSingleByteReadsToo() throws IOException {
		InputStream in = RequestSizeLimitFilter.limit(new ByteArrayInputStream(new byte[3]), 2);
		in.read();
		in.read();
		assertThatThrownBy(in::read).isInstanceOf(RequestSizeLimitFilter.TooLargeException.class);
	}
}
