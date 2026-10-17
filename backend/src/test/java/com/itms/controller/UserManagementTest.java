package com.itms.controller;

import com.itms.common.GlobalExceptionHandler;
import com.itms.entity.User;
import com.itms.repository.UserRepository;
import com.itms.service.UserManagementService;
import com.itms.service.SystemLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserManagementTest {
    UserRepository users = mock(UserRepository.class);
    AtomicReference<User> saved = new AtomicReference<>();
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(new UserManagementService(users, mock(JdbcTemplate.class), mock(SystemLogService.class))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.saveAndFlush(any())).thenAnswer(call -> { User user = call.getArgument(0); user.setId(10L); saved.set(user); return user; });
    }
    @Test void createsHashedPasswordAndNeverReturnsIt() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"chosen-password\",\"role\":\"DRIVER\",\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        assertThat(new BCryptPasswordEncoder().matches("chosen-password", saved.get().getPassword())).isTrue();
    }
    @Test void rejectsEntityIdInjectionAndMissingPassword() throws Exception {
        for (String body : new String[]{
                "{\"id\":1,\"username\":\"admin\",\"password\":\"chosen-password\",\"role\":\"ADMIN\"}",
                "{\"username\":\"newuser\",\"role\":\"DRIVER\"}"}) {
            mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(saved.get()).isNull();
    }
    @Test void rejectsRenamingExistingUsernameInsteadOfSilentlyIgnoringIt() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).username("existing")
                .role("DRIVER").enabled(true).password("hash").build()));
        mvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"renamed\",\"role\":\"DRIVER\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }
    @Test void returns404ForMissingUser() throws Exception {
        mvc.perform(delete("/api/users/999")).andExpect(status().isNotFound());
    }
    @Test void duplicateUsernameReturnsConflictAndInvalidRoleFailsValidation() throws Exception {
        when(users.existsByUsername("existing")).thenReturn(true);
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"existing\",\"password\":\"chosen-password\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"chosen-password\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
        assertThat(saved.get()).isNull();
    }
}
