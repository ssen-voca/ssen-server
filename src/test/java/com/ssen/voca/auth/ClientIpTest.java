package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

	private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

	@Test
	void usesTheConnectionAddressWhenNoHeaderIsConfigured() {
		ClientIp clientIp = new ClientIp("");

		assertThat(clientIp.of(request("10.0.0.2", "1.1.1.1"))).isEqualTo("10.0.0.2");
	}

	@Test
	void usesTheLastValueOfTheConfiguredHeader() {
		ClientIp clientIp = new ClientIp("X-Forwarded-For");

		assertThat(clientIp.of(request("10.0.0.2", "6.6.6.6, 7.7.7.7 ,  9.9.9.9 "))).isEqualTo("9.9.9.9");
		assertThat(clientIp.of(request("10.0.0.2", "9.9.9.9"))).isEqualTo("9.9.9.9");
	}

	@Test
	void fallsBackToTheConnectionAddressWhenTheHeaderIsMissingOrBlank() {
		ClientIp clientIp = new ClientIp("X-Forwarded-For");

		assertThat(clientIp.of(request("10.0.0.2", null))).isEqualTo("10.0.0.2");
		assertThat(clientIp.of(request("10.0.0.2", "  "))).isEqualTo("10.0.0.2");
		assertThat(clientIp.of(request("10.0.0.2", "1.1.1.1,"))).isEqualTo("10.0.0.2");
	}

	@Test
	void truncatesTo100Characters() {
		ClientIp clientIp = new ClientIp("X-Forwarded-For");

		assertThat(clientIp.of(request("10.0.0.2", "a".repeat(150)))).hasSize(100);
		assertThat(new ClientIp("").of(request("b".repeat(150), null))).hasSize(100);
	}
}
