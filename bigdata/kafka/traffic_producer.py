"""
Kafka 交通数据生产者
======================
模拟产生实时交通数据,发送到Kafka供Spark消费

数据流:
    传感器/摄像头 -> Kafka Producer -> Kafka Topic
                                      -> Spark Streaming
                                      -> PostgreSQL/Redis
"""

import json
import time
import random
from datetime import datetime
from typing import Dict, List
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

try:
    from kafka import KafkaProducer
    KAFKA_AVAILABLE = True
except ImportError:
    KAFKA_AVAILABLE = False
    logger.warning("kafka-python not installed, using mock mode")


# 模拟路口数据
INTERSECTIONS = [
    {"id": "INT-001", "name": "天安门路口", "lng": 116.397, "lat": 39.908},
    {"id": "INT-002", "name": "东单路口", "lng": 116.410, "lat": 39.908},
    {"id": "INT-003", "name": "建国门路口", "lng": 116.420, "lat": 39.908},
    {"id": "INT-004", "name": "地安门路口", "lng": 116.397, "lat": 39.920},
    {"id": "INT-005", "name": "鼓楼路口", "lng": 116.410, "lat": 39.920},
    {"id": "INT-006", "name": "雍和宫路口", "lng": 116.420, "lat": 39.920},
    {"id": "INT-007", "name": "前门路口", "lng": 116.397, "lat": 39.898},
    {"id": "INT-008", "name": "崇文门路口", "lng": 116.410, "lat": 39.898},
    {"id": "INT-009", "name": "东便门路口", "lng": 116.420, "lat": 39.898},
]

DIRECTIONS = ["NORTH", "SOUTH", "EAST", "WEST"]
DATA_SOURCES = ["SENSOR", "CAMERA", "GPS"]


class TrafficDataGenerator:
    """
    交通数据生成器
    模拟真实交通传感器数据
    """
    
    def __init__(self):
        self.base_volumes = {}  # 各路口基准车流量
        
        # 初始化基准车流量(高峰期更高)
        for intersection in INTERSECTIONS:
            # 市中心路口车流量更高
            is_center = intersection["id"] in ["INT-001", "INT-002", "INT-005"]
            base = random.randint(600, 1200) if is_center else random.randint(300, 800)
            self.base_volumes[intersection["id"]] = base
    
    def get_current_hour_factor(self) -> float:
        """
        根据当前小时计算流量因子
        
        高峰时段(7-9, 17-19): 1.5x
        平峰时段: 1.0x
        低谷时段(0-5): 0.3x
        """
        hour = datetime.now().hour
        
        if 7 <= hour <= 9 or 17 <= hour <= 19:
            return 1.5  # 早/晚高峰
        elif 0 <= hour <= 5:
            return 0.3  # 夜间低谷
        elif 11 <= hour <= 13:
            return 1.2  # 午间小高峰
        else:
            return 0.8  # 平峰
    
    def generate_data_point(self, intersection: Dict) -> Dict:
        """
        生成单个路口数据点
        """
        base_volume = self.base_volumes[intersection["id"]]
        hour_factor = self.get_current_hour_factor()
        
        # 加入随机波动
        volume = int(base_volume * hour_factor * random.uniform(0.7, 1.3))
        
        # 速度与车流量负相关
        if volume > 1000:
            speed = random.uniform(10, 30)
        elif volume > 600:
            speed = random.uniform(25, 50)
        else:
            speed = random.uniform(40, 70)
        
        direction = random.choice(DIRECTIONS)
        
        return {
            "intersection_id": intersection["id"],
            "intersection_name": intersection["name"],
            "longitude": intersection["lng"],
            "latitude": intersection["lat"],
            "vehicle_count": volume,
            "avg_speed": round(speed, 1),
            "occupancy_rate": round(min(100, volume / 20 + random.uniform(-5, 5)), 1),
            "direction": direction,
            "record_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "data_source": random.choice(DATA_SOURCES)
        }
    
    def generate_batch(self) -> List[Dict]:
        """
        生成一批数据(每个路口四个方向各一条)
        """
        batch = []
        for intersection in INTERSECTIONS:
            for _ in range(random.randint(1, 4)):
                data = self.generate_data_point(intersection)
                batch.append(data)
        return batch


class KafkaTrafficProducer:
    """
    Kafka交通数据生产者
    """
    
    def __init__(self, bootstrap_servers: str = "localhost:9092",
                 topic: str = "traffic-data"):
        self.topic = topic
        self.generator = TrafficDataGenerator()
        
        if KAFKA_AVAILABLE:
            self.producer = KafkaProducer(
                bootstrap_servers=bootstrap_servers,
                value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
                acks="all",
                retries=3
            )
            logger.info(f"Kafka生产者已连接到 {bootstrap_servers}")
        else:
            self.producer = None
            logger.info("使用模拟模式(数据输出到控制台)")
    
    def send_data(self, data: Dict):
        """发送单条数据"""
        if self.producer:
            future = self.producer.send(self.topic, value=data)
            record_metadata = future.get(timeout=10)
            logger.debug(f"发送到分区 {record_metadata.partition}, "
                        f"偏移 {record_metadata.offset}")
        else:
            logger.info(f"[模拟] {json.dumps(data, ensure_ascii=False)}")
    
    def send_batch(self, data_list: List[Dict]):
        """批量发送数据"""
        for data in data_list:
            self.send_data(data)
    
    def start_simulation(self, interval: int = 5, continuous: bool = True):
        """
        启动模拟数据生产
        
        Args:
            interval: 发送间隔(秒)
            continuous: 是否持续发送
        """
        logger.info(f"开始模拟数据生产(间隔={interval}s, 持续={continuous})")
        
        try:
            while True:
                batch = self.generator.generate_batch()
                logger.info(f"生成 {len(batch)} 条交通数据")
                self.send_batch(batch)
                
                if not continuous:
                    break
                    
                time.sleep(interval)
                
        except KeyboardInterrupt:
            logger.info("数据生产已停止")
        finally:
            if self.producer:
                self.producer.close()
    
    def start_realtime_simulation(self):
        """启动实时模拟(按实际时间节奏产生数据)"""
        logger.info("启动实时交通模拟...")
        
        try:
            while True:
                now = datetime.now()
                # 每5秒生成一批数据
                batch = self.generator.generate_batch()
                logger.info(f"[{now.strftime('%H:%M:%S')}] "
                          f"生成 {len(batch)} 条数据")
                self.send_batch(batch)
                time.sleep(5)
                
        except KeyboardInterrupt:
            logger.info("实时模拟已停止")
            if self.producer:
                self.producer.close()


if __name__ == "__main__":
    producer = KafkaTrafficProducer()
    producer.start_simulation(interval=5, continuous=True)
