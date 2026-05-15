package io.github.xbta4224j.scanner.config

import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest @Autowired constructor(
    private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {

    @Test
    fun `dashboard root is public`() {
        mockMvc.perform(get("/")).andExpect(status().isOk)
    }

    @Test
    fun `actuator health is public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    @Test
    fun `admin queue requires auth - 401 without credentials`() {
        mockMvc.perform(get("/admin/queue")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin queue accessible with valid basic creds`() {
        mockMvc.perform(get("/admin/queue").with(httpBasic("admin", "admin")))
            .andExpect(status().isOk)
    }

    @Test
    fun `admin queue rejected with wrong password`() {
        mockMvc.perform(get("/admin/queue").with(httpBasic("admin", "wrong")))
            .andExpect(status().isUnauthorized)
    }
}
