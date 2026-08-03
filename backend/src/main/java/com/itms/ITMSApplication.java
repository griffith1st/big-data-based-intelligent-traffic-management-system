package com.itms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 基于大数据的智能交通管理系统 (ITMS-BD)
 * 主启动类
 *
 * @author ITMS-BD Team
 * @since 2026
 */
@SpringBootApplication
@EnableScheduling
public class ITMSApplication {

    public static void main(String[] args) {
        SpringApplication.run(ITMSApplication.class, args);
        System.out.println("==============================================");
        System.out.println("  ITMS-BD 智能交通管理系统启动成功!");
        System.out.println("  Swagger UI: http://localhost:8080/swagger-ui.html");
        System.out.println("==============================================");
    }
}
