package com.itms.repository;

import com.itms.entity.SystemLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;


@Repository
public interface SystemLogRepository extends JpaRepository<SystemLog, Long> {

 
    List<SystemLog> findAllByOrderByCreatedAtDesc();


    List<SystemLog> findByUserIdOrderByCreatedAtDesc(Long userId);


    List<SystemLog> findByLogTypeOrderByCreatedAtDesc(String logType);


    List<SystemLog> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);


    List<SystemLog> findByModuleOrderByCreatedAtDesc(String module);
}
