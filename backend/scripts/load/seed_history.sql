\set ON_ERROR_STOP on
DO $$ BEGIN
  IF current_database() <> 'itms_concurrency_load' THEN
    RAISE EXCEPTION 'Use the isolated load database';
  END IF;
  IF (SELECT count(*) FROM traffic_data) <> 56 THEN
    RAISE EXCEPTION 'Expected exactly 56 seeded rows; do not run twice';
  END IF;
END $$;
INSERT INTO traffic_data (intersection_id, intersection_name, longitude, latitude,
 vehicle_count, avg_speed, occupancy_rate, congestion_level, direction, record_time, data_source, created_at)
SELECT intersection_id, intersection_name, longitude, latitude,
 vehicle_count, avg_speed, occupancy_rate, congestion_level, direction,
 record_time - series.n * interval '5 seconds', 'LOAD_FIXTURE', now()
FROM traffic_data CROSS JOIN generate_series(1,500) AS series(n);
ANALYZE traffic_data;
SELECT count(*) AS rows, count(DISTINCT intersection_id) AS intersections FROM traffic_data;
