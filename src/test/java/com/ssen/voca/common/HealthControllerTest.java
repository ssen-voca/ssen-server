package com.ssen.voca.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointIsPubliclyAccessible() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk());
	}

	@Test
	void dbHealthIsPubliclyAccessibleAndUpWhenTheDatabaseIsReachable() throws Exception {
		mockMvc.perform(get("/api/health/db"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void dbHealthReturns503WithoutLeakingDetailsWhenTheConnectionFails() throws Exception {
		DataSource broken = mock(DataSource.class);
		when(broken.getConnection()).thenThrow(new SQLException("jdbc:postgresql://secret-host:5432/ssen refused"));

		var response = new HealthController(broken).db();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).containsExactly(java.util.Map.entry("status", "DOWN"));
	}

	@Test
	void dbHealthReturns503WhenTheConnectionIsNotValid() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(false);

		assertThat(new HealthController(dataSource).db().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void livenessEndpointDoesNotTouchTheDatabase() {
		DataSource broken = mock(DataSource.class);

		assertThat(new HealthController(broken).health()).containsEntry("status", "UP");
	}
}
