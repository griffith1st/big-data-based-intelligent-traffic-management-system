package com.itms.scheduler;

import com.itms.repository.TrafficDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理过期交通数据，防止 traffic_data 表无限增长
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupScheduler {

    private final TrafficDataRepository trafficDataRepository;

    /** 每 30 分钟清理超过 2 小时的历史数据 */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanOldTrafficData() {
        try {
            int deleted = trafficDataRepository.deleteOldData(2);
            if (deleted > 0) {
                log.info("Cleaned {} old traffic_data records (older than 2 hours)", deleted);
            }
        } catch (Exception e) {
            log.warn("Data cleanup skipped: {}", e.getMessage());
        }
    }
}
