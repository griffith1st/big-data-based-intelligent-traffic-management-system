CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(100),
    email VARCHAR(100) UNIQUE,
    phone VARCHAR(20),
    role VARCHAR(30) NOT NULL DEFAULT 'TRAFFIC_MANAGER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    district VARCHAR(100),
    avatar_url VARCHAR(500),
    last_login_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS traffic_data (
    id BIGSERIAL PRIMARY KEY,
    intersection_id VARCHAR(50) NOT NULL,
    intersection_name VARCHAR(200),
    longitude DOUBLE PRECISION NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    vehicle_count INTEGER NOT NULL,
    avg_speed DOUBLE PRECISION,
    occupancy_rate DOUBLE PRECISION,
    congestion_level INTEGER,
    direction VARCHAR(10),
    record_time TIMESTAMP NOT NULL,
    data_source VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_traffic_intersection_time ON traffic_data(intersection_id, record_time);
CREATE INDEX IF NOT EXISTS idx_traffic_record_time ON traffic_data(record_time);

CREATE TABLE IF NOT EXISTS traffic_predictions (
    id BIGSERIAL PRIMARY KEY,
    intersection_id VARCHAR(50) NOT NULL,
    intersection_name VARCHAR(200),
    prediction_batch_id VARCHAR(40),
    prediction_time TIMESTAMP NOT NULL,
    predicted_volume INTEGER,
    predicted_speed DOUBLE PRECISION,
    predicted_congestion INTEGER,
    confidence DOUBLE PRECISION,
    model_version VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pred_intersection ON traffic_predictions(intersection_id);
CREATE INDEX IF NOT EXISTS idx_pred_time ON traffic_predictions(prediction_time);
CREATE INDEX IF NOT EXISTS idx_pred_batch ON traffic_predictions(prediction_batch_id);

CREATE TABLE IF NOT EXISTS traffic_signals (
    id BIGSERIAL PRIMARY KEY,
    intersection_id VARCHAR(50) NOT NULL,
    direction VARCHAR(10),
    green_duration INTEGER,
    red_duration INTEGER,
    yellow_duration INTEGER,
    cycle_duration INTEGER,
    phase_id INTEGER,
    optimization_status VARCHAR(20) DEFAULT 'PENDING',
    strategy VARCHAR(20) DEFAULT 'FIXED',
    applied_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS traffic_alerts (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    alert_level VARCHAR(20) NOT NULL,
    alert_type VARCHAR(30),
    intersection_id VARCHAR(50),
    longitude DOUBLE PRECISION,
    latitude DOUBLE PRECISION,
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
    handler VARCHAR(50),
    handled_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alert_level ON traffic_alerts(alert_level);
CREATE INDEX IF NOT EXISTS idx_alert_time ON traffic_alerts(created_at);

CREATE TABLE IF NOT EXISTS route_plans (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    start_intersection_id VARCHAR(50),
    start_intersection_name VARCHAR(200),
    end_intersection_id VARCHAR(50),
    end_intersection_name VARCHAR(200),
    start_lng DOUBLE PRECISION NOT NULL,
    start_lat DOUBLE PRECISION NOT NULL,
    end_lng DOUBLE PRECISION NOT NULL,
    end_lat DOUBLE PRECISION NOT NULL,
    strategy VARCHAR(20),
    route_points TEXT,
    total_distance DOUBLE PRECISION,
    estimated_time INTEGER,
    carbon_emission DOUBLE PRECISION,
    intersections TEXT,
    route_edges TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS system_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(50),
    log_type VARCHAR(20),
    module VARCHAR(100),
    description TEXT,
    ip_address VARCHAR(50),
    method VARCHAR(10),
    request_uri VARCHAR(500),
    request_params TEXT,
    execution_time BIGINT,
    status VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_user ON system_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_log_time ON system_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_log_type ON system_logs(log_type);

INSERT INTO users (username, password, real_name, email, role, enabled, district)
VALUES
('admin', '123456', '系统管理员', 'admin@itms.com', 'ADMIN', TRUE, '全城'),
('manager1', '123456', '张三', 'zhangsan@itms.com', 'TRAFFIC_MANAGER', TRUE, '朝阳区'),
('manager2', '123456', '李四', 'lisi@itms.com', 'TRAFFIC_MANAGER', TRUE, '海淀区'),
('driver1', '123456', '王五', 'wangwu@itms.com', 'DRIVER', TRUE, '朝阳区'),
('driver2', '123456', '赵六', 'zhaoliu@itms.com', 'DRIVER', TRUE, '海淀区')
ON CONFLICT (username) DO UPDATE SET
    password = EXCLUDED.password,
    real_name = EXCLUDED.real_name,
    email = EXCLUDED.email,
    role = EXCLUDED.role,
    enabled = EXCLUDED.enabled,
    district = EXCLUDED.district,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO traffic_signals (intersection_id, direction, green_duration, red_duration, yellow_duration, cycle_duration, phase_id, optimization_status, strategy)
VALUES
('INT-001', 'NORTH', 45, 45, 5, 95, 1, 'APPLIED', 'FIXED'),
('INT-001', 'SOUTH', 45, 45, 5, 95, 1, 'APPLIED', 'FIXED'),
('INT-001', 'EAST', 30, 60, 5, 95, 2, 'APPLIED', 'FIXED'),
('INT-001', 'WEST', 30, 60, 5, 95, 2, 'APPLIED', 'FIXED'),
('INT-002', 'NORTH', 40, 50, 5, 95, 1, 'APPLIED', 'FIXED'),
('INT-002', 'SOUTH', 40, 50, 5, 95, 1, 'APPLIED', 'FIXED'),
('INT-002', 'EAST', 35, 55, 5, 95, 2, 'APPLIED', 'FIXED'),
('INT-002', 'WEST', 35, 55, 5, 95, 2, 'APPLIED', 'FIXED');
