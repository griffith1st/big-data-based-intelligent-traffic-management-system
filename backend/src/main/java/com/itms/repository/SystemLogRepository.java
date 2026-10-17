package com.itms.repository;

import com.itms.entity.SystemLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;


@Repository
public interface SystemLogRepository extends JpaRepository<SystemLog, Long>, JpaSpecificationExecutor<SystemLog> {

    long countByLogType(String logType);
    long countByStatus(String status);

    @Query(value = "SELECT CAST(created_at AS DATE), COUNT(*) FROM system_logs "
            + "WHERE created_at >= :since GROUP BY CAST(created_at AS DATE) "
            + "ORDER BY CAST(created_at AS DATE)", nativeQuery = true)
    List<Object[]> countByDaySince(@Param("since") LocalDateTime since);

 
    List<SystemLog> findAllByOrderByCreatedAtDesc();


    List<SystemLog> findByUserIdOrderByCreatedAtDesc(Long userId);


    List<SystemLog> findByLogTypeOrderByCreatedAtDesc(String logType);


    List<SystemLog> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);


    List<SystemLog> findByModuleOrderByCreatedAtDesc(String module);
}
