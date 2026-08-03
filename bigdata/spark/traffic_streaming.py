"""
PySpark 交通数据流处理
========================
功能:
    1. 从Kafka实时消费交通数据
    2. 使用Spark Streaming进行数据清洗和聚合
    3. 计算实时拥堵指标
    4. 写入PostgreSQL数据库

架构:
    Kafka -> Spark Streaming -> PostgreSQL/Redis
              -> 实时仪表盘(WebSocket)
"""

from pyspark.sql import SparkSession
from pyspark.sql.functions import (
    from_json, col, window, avg, max, min,
    count, when, udf, struct, to_json
)
from pyspark.sql.types import (
    StructType, StructField, StringType,
    IntegerType, DoubleType, TimestampType
)
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# 定义数据Schema
TRAFFIC_SCHEMA = StructType([
    StructField("intersection_id", StringType(), True),
    StructField("intersection_name", StringType(), True),
    StructField("longitude", DoubleType(), True),
    StructField("latitude", DoubleType(), True),
    StructField("vehicle_count", IntegerType(), True),
    StructField("avg_speed", DoubleType(), True),
    StructField("occupancy_rate", DoubleType(), True),
    StructField("direction", StringType(), True),
    StructField("record_time", StringType(), True),
    StructField("data_source", StringType(), True)
])


class TrafficStreamProcessor:
    """
    交通数据流处理器
    """
    
    def __init__(self, app_name="ITMS-BD-Streaming"):
        self.spark = SparkSession.builder \
            .appName(app_name) \
            .config("spark.sql.shuffle.partitions", "10") \
            .config("spark.streaming.stopGracefullyOnShutdown", "true") \
            .getOrCreate()
        
        self.spark.sparkContext.setLogLevel("WARN")
        logger.info("Spark Session 初始化完成")
    
    def read_from_kafka(self, bootstrap_servers: str = "localhost:9092",
                        topic: str = "traffic-data"):
        """
        从Kafka读取交通数据流
        
        Args:
            bootstrap_servers: Kafka服务器地址
            topic: Kafka主题
        
        Returns:
            DataFrame: 解析后的交通数据
        """
        df = self.spark.readStream \
            .format("kafka") \
            .option("kafka.bootstrap.servers", bootstrap_servers) \
            .option("subscribe", topic) \
            .option("startingOffsets", "latest") \
            .load()
        
        # 解析JSON数据
        parsed = df.select(
            from_json(col("value").cast("string"), TRAFFIC_SCHEMA).alias("data")
        ).select("data.*")
        
        return parsed
    
    def compute_congestion_level(self, vehicle_count, avg_speed):
        """
        计算拥堵等级
        
        伪代码:
            if vehicle_count < 500 and avg_speed > 50:
                return 0  // 畅通
            elif vehicle_count < 1000 and avg_speed > 35:
                return 1  // 轻度拥堵
            elif vehicle_count < 2000 and avg_speed > 20:
                return 2  // 中度拥堵
            else:
                return 3  // 严重拥堵
        """
        if vehicle_count < 500 and avg_speed > 50:
            return 0
        elif vehicle_count < 1000 and avg_speed > 35:
            return 1
        elif vehicle_count < 2000 and avg_speed > 20:
            return 2
        else:
            return 3
    
    def process_stream(self, df):
        """
        数据流处理: 清洗 -> 转换 -> 聚合
        
        数据处理流水线:
            1. 过滤异常数据(负值、空值)
            2. 计算拥堵等级
            3. 按路口和时间窗口聚合统计
            4. 生成实时指标
        """
        # 1. 数据清洗
        cleaned = df.filter(
            (col("vehicle_count") >= 0) &
            (col("avg_speed") >= 0) &
            (col("intersection_id").isNotNull())
        )
        
        # 2. 计算拥堵等级(UDF)
        congestion_udf = udf(self.compute_congestion_level, IntegerType())
        
        transformed = cleaned.withColumn(
            "congestion_level",
            congestion_udf(col("vehicle_count"), col("avg_speed"))
        )
        
        # 3. 按路口和时间窗口聚合(每5分钟)
        aggregated = transformed \
            .withWatermark("record_time", "10 minutes") \
            .groupBy(
                col("intersection_id"),
                window(col("record_time"), "5 minutes")
            ) \
            .agg(
                avg("vehicle_count").alias("avg_volume"),
                avg("avg_speed").alias("avg_speed"),
                max("congestion_level").alias("max_congestion"),
                count("*").alias("data_points")
            )
        
        return aggregated
    
    def write_to_console(self, df):
        """输出到控制台(调试用)"""
        query = df.writeStream \
            .outputMode("update") \
            .format("console") \
            .option("truncate", "false") \
            .start()
        return query
    
    def write_to_postgresql(self, df, jdbc_url: str, table: str,
                            user: str, password: str):
        """写入PostgreSQL数据库"""
        query = df.writeStream \
            .foreachBatch(lambda batch_df, epoch_id:
                          batch_df.write
                          .format("jdbc")
                          .option("url", jdbc_url)
                          .option("dbtable", table)
                          .option("user", user)
                          .option("password", password)
                          .mode("append")
                          .save()) \
            .start()
        return query
    
    def start_streaming(self, source_topic: str = "traffic-data",
                        output_mode: str = "console"):
        """启动流处理"""
        df = self.read_from_kafka(topic=source_topic)
        processed = self.process_stream(df)
        
        if output_mode == "console":
            query = self.write_to_console(processed)
        else:
            query = self.write_to_postgresql(
                processed,
                "jdbc:postgresql://postgres:5432/itms_bd",
                "traffic_aggregations",
                "itms_admin",
                "itms123456"
            )
        
        query.awaitTermination()
    
    def stop(self):
        """停止SparkSession"""
        self.spark.stop()
        logger.info("Spark Session 已停止")


class TrafficBatchProcessor:
    """
    交通数据批处理器(离线分析)
    """
    
    def __init__(self):
        self.spark = SparkSession.builder \
            .appName("ITMS-BD-Batch") \
            .getOrCreate()
    
    def analyze_daily_traffic(self, input_path: str):
        """
        日交通流量分析
        
        功能:
            - 各路口日流量统计
            - 高峰时段识别
            - 拥堵趋势分析
            - 数据写入数据仓库
        """
        df = self.spark.read \
            .option("header", "true") \
            .csv(input_path)
        
        # 按路口和小时聚合
        hourly_stats = df.groupBy(
            col("intersection_id"),
            hour(col("record_time")).alias("hour")
        ).agg(
            avg("vehicle_count").alias("avg_hourly_volume"),
            max("vehicle_count").alias("peak_volume"),
            avg("avg_speed").alias("avg_speed"),
            avg("occupancy_rate").alias("avg_occupancy")
        ).orderBy("intersection_id", "hour")
        
        # 识别高峰时段
        peak_hours = hourly_stats.filter(
            col("avg_hourly_volume") > 1000
        ).orderBy(col("avg_hourly_volume").desc())
        
        return hourly_stats, peak_hours
    
    def traffic_trend_analysis(self, days: int = 30):
        """
        交通趋势分析
        
        分析近N天的交通数据变化趋势,
        为LSTM模型提供训练特征
        """
        # 从PostgreSQL读取数据
        df = self.spark.read \
            .format("jdbc") \
            .option("url", "jdbc:postgresql://postgres:5432/itms_bd") \
            .option("dbtable", "traffic_data") \
            .option("user", "itms_admin") \
            .option("password", "itms123456") \
            .load()
        
        # 时间序列特征工程
        features = df.withColumn("date", to_date(col("record_time"))) \
            .groupBy("intersection_id", "date") \
            .agg(
                avg("vehicle_count").alias("daily_avg_volume"),
                max("vehicle_count").alias("daily_peak_volume"),
                stddev("vehicle_count").alias("volume_std")
            ) \
            .orderBy("intersection_id", "date")
        
        return features


if __name__ == "__main__":
    # 测试: 启动流处理
    processor = TrafficStreamProcessor()
    
    try:
        print("启动交通数据流处理...")
        print("从Kafka topic 'traffic-data' 读取数据")
        processor.start_streaming(output_mode="console")
    except KeyboardInterrupt:
        processor.stop()
        print("流处理已停止")
