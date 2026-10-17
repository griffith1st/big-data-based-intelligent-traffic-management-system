package com.itms.integration;

import com.itms.common.GlobalExceptionHandler;
import com.itms.controller.SystemLogController;
import com.itms.entity.SystemLog;
import com.itms.repository.SystemLogRepository;
import com.itms.service.SystemLogService;
import com.itms.service.impl.SystemLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.datasource.username=postgres", "spring.datasource.password="})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(SystemLogServiceImpl.class)
class SystemLogPostgresIT {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
        assertThat(System.getProperty("test.postgres.isolated")).isEqualTo("true");
        int port = Integer.parseInt(System.getProperty("test.postgres.port"));
        assertThat(port).isBetween(1024,65535);
        r.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"+port+"/itms_logs_it");
    }
    @Autowired SystemLogRepository repository;
    @Autowired SystemLogService service;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new SystemLogController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        repository.deleteAll();
        repository.saveAndFlush(SystemLog.builder().userId(10L).username("alice")
                .logType("LOGIN").module("认证").description("fixture").status("SUCCESS").build());
        repository.saveAndFlush(SystemLog.builder().userId(10L).username("alice")
                .logType("OPERATION").module("用户").description("fixture").status("FAILED").build());
        repository.saveAndFlush(SystemLog.builder().userId(20L).username("bob")
                .logType("LOGIN").module("认证").description("fixture").status("SUCCESS").build());
    }

    @Test void combinedFiltersAreAppliedTogether() throws Exception {
        mvc.perform(get("/api/logs").param("logType","LOGIN").param("userId","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].logType").value("LOGIN"));
    }

    @Test void pagesContainStableRowsAndTotalCount() throws Exception {
        mvc.perform(get("/api/logs/page").param("page","0").param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].username").value("bob"));
        mvc.perform(get("/api/logs/page").param("page","1").param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("alice"));
    }

    @Test void excessivePageSizeAndNegativePageAreRejected() throws Exception {
        mvc.perform(get("/api/logs/page").param("size","101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/logs/page").param("page","-1"))
                .andExpect(status().isBadRequest());
    }

    @Test void summaryUsesAllRowsAndFullDatesButOnlySevenDaysForTrend() throws Exception {
        jdbc.update("UPDATE system_logs SET created_at=? WHERE user_id=20", LocalDate.now().minusDays(8).atStartOfDay());
        mvc.perform(get("/api/logs/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.byType.LOGIN").value(2))
                .andExpect(jsonPath("$.data.byDay['"+LocalDate.now()+"']").value(2));
    }
}
