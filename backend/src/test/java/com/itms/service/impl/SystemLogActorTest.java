package com.itms.service.impl;

import com.itms.entity.SystemLog;
import com.itms.repository.SystemLogRepository;
import com.itms.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemLogActorTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void userOperationRecordsTheAuthenticatedActor() {
        var repository = mock(SystemLogRepository.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(41L, "manager1", "TRAFFIC_MANAGER", 0), null, List.of()));
        new SystemLogServiceImpl(repository).log("OPERATION", "signal", "apply plan", "SUCCESS");
        var saved = ArgumentCaptor.forClass(SystemLog.class); verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("manager1");
        assertThat(saved.getValue().getUserId()).isEqualTo(41L);
    }
}
