package com.itms.repository;

import com.itms.entity.TrafficAlert;
import com.itms.entity.TrafficData;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

/** Atomic condition updates participate in the service's PostgreSQL transaction. */
@Repository
@RequiredArgsConstructor
public class TrafficAlertWriter {
    private final JdbcTemplate jdbc;

    public void upsert(TrafficAlert alert, Long trafficDataId) {
        lock(alert.getActiveKey());
        if (isOlderPersistedRealtime(alert.getSource(), alert.getIntersectionId(), alert.getDirection(), trafficDataId)) return;
        // Persisted realtime observations use the traffic row order, not timestamp alone:
        // a higher ID at the same timestamp can be a genuinely new congestion event.
        if (!"REALTIME".equals(alert.getSource()) || trafficDataId == null) {
            Boolean alreadySuperseded = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM traffic_alerts WHERE source=? AND intersection_id=? AND direction=?
                    AND status='RESOLVED' AND last_seen_at >= ?)
                """, Boolean.class, alert.getSource(), alert.getIntersectionId(), alert.getDirection(), alert.getLastSeenAt());
            if (Boolean.TRUE.equals(alreadySuperseded)) return;
        }
        jdbc.update("""
            INSERT INTO traffic_alerts(active_key,title,content,alert_level,alert_type,intersection_id,
                longitude,latitude,status,direction,source,last_seen_at,created_at)
            VALUES (?,?,?,?,?,?,?,?, 'UNREAD',?,?,?,CURRENT_TIMESTAMP)
            ON CONFLICT (active_key) DO UPDATE SET
                title=EXCLUDED.title, content=EXCLUDED.content, alert_level=EXCLUDED.alert_level,
                longitude=EXCLUDED.longitude, latitude=EXCLUDED.latitude, last_seen_at=EXCLUDED.last_seen_at
            WHERE traffic_alerts.last_seen_at IS NULL OR EXCLUDED.last_seen_at >= traffic_alerts.last_seen_at
            """, alert.getActiveKey(),alert.getTitle(),alert.getContent(),alert.getAlertLevel(),alert.getAlertType(),
                alert.getIntersectionId(),alert.getLongitude(),alert.getLatitude(),alert.getDirection(),
                alert.getSource(),alert.getLastSeenAt());
    }

    public void resolveRecovered(String key, String source, TrafficData observation, LocalDateTime observedAt) {
        lock(key);
        if (isOlderPersistedRealtime(source, observation.getIntersectionId(), observation.getDirection(), observation.getId())) return;
        jdbc.update("""
            UPDATE traffic_alerts SET status='RESOLVED',handler='SYSTEM',handled_at=CURRENT_TIMESTAMP,
                active_key=NULL,last_seen_at=? WHERE active_key=? AND status <> 'RESOLVED'
                AND (last_seen_at IS NULL OR last_seen_at <= ?)
            """,observedAt,key,observedAt);
    }
    private boolean isOlderPersistedRealtime(String source, String intersectionId, String direction, Long observationId) {
        if (!"REALTIME".equals(source) || observationId == null) return false;
        // Read after the condition lock. READ COMMITTED now sees a newer writer
        // that committed while this transaction was waiting for that lock.
        var latestIds = jdbc.queryForList("""
            SELECT id FROM traffic_data WHERE intersection_id=? AND direction=?
            ORDER BY record_time DESC,id DESC LIMIT 1
            """, Long.class, intersectionId, direction);
        // Matching IDs avoids Java nanosecond vs PostgreSQL microsecond comparisons.
        return latestIds.isEmpty() || !observationId.equals(latestIds.get(0));
    }

    private void lock(String key) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) locked", Integer.class, key);
    }

}
