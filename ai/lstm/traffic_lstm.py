"""
LSTM 交通流量预测模型
========================
算法原理:
    LSTM(长短期记忆网络)通过时间序列数据学习交通流的时序特征,
    利用过去N个时间步的数据预测未来30分钟的交通流量。

伪代码:
    function LSTM_Predict(traffic_history):
        // 1. 数据预处理
        data = normalize(traffic_history)
        
        // 2. 构建时间序列样本
        X, y = create_sequences(data, lookback=12)
        
        // 3. LSTM模型推理
        model = load_model("lstm_traffic.h5")
        predictions = model.predict(X)
        
        // 4. 反归一化
        result = denormalize(predictions)
        
        // 5. 返回未来30分钟预测(每5分钟一个点)
        return result

算法流程:
    输入: 历史交通流量数据(过去1小时,12个时间点)
    -> 归一化处理
    -> 构建时序特征
    -> LSTM层提取时序特征(Hidden_dim=64)
    -> Dropout防止过拟合(rate=0.2)
    -> 全连接层输出预测
    -> 反归一化
    输出: 未来30分钟流量预测(6个时间点,每5分钟)
"""

import numpy as np
import pandas as pd
from datetime import datetime, timedelta
import json
import logging
from typing import List, Dict, Any

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class TrafficLSTM:
    """
    LSTM交通流量预测模型实现
    对应于: ai/lstm/lstm_model.py (PyTorch实现)
    """
    
    def __init__(self, lookback: int = 12, forecast_steps: int = 6):
        """
        初始化LSTM预测器
        
        Args:
            lookback: 历史时间步数(默认12步=1小时,每5分钟一个点)
            forecast_steps: 预测步数(默认6步=30分钟,每5分钟一个点)
        """
        self.lookback = lookback
        self.forecast_steps = forecast_steps
        
        # 模型参数(对应PyTorch模型)
        self.input_dim = 1        # 输入维度: 车流量
        self.hidden_dim = 64      # 隐藏层维度
        self.num_layers = 2       # LSTM层数
        self.dropout = 0.2        # Dropout比率
        
        # 归一化参数
        self.mean = None
        self.std = None
        
    def preprocess(self, data: np.ndarray) -> np.ndarray:
        """
        数据预处理: 归一化
        
        Args:
            data: 原始流量数据
            
        Returns:
            归一化后的数据
        """
        self.mean = np.mean(data)
        self.std = np.std(data) + 1e-8
        return (data - self.mean) / self.std
    
    def denormalize(self, data: np.ndarray) -> np.ndarray:
        """反归一化"""
        return data * self.std + self.mean
    
    def create_sequences(self, data: np.ndarray) -> tuple:
        """
        创建时间序列样本
        
        Args:
            data: 归一化后的数据
            
        Returns:
            X: 输入序列, y: 目标序列
        """
        X, y = [], []
        for i in range(len(data) - self.lookback - self.forecast_steps + 1):
            X.append(data[i:i + self.lookback])
            y.append(data[i + self.lookback:i + self.lookback + self.forecast_steps])
        return np.array(X), np.array(y)
    
    def predict(self, history_data: List[float]) -> List[Dict[str, Any]]:
        """
        预测未来30分钟交通流量
        
        Args:
            history_data: 历史流量数据列表(至少12个点,每5分钟)
            
        Returns:
            预测结果列表,每个元素包含预测时间和流量值
        """
        logger.info(f"LSTM预测开始, 输入数据长度: {len(history_data)}")
        
        # 1. 数据预处理
        data = np.array(history_data, dtype=np.float32)
        normalized = self.preprocess(data)
        
        # 2. 构建输入序列
        X, _ = self.create_sequences(normalized)
        
        if len(X) == 0:
            # 数据不足时使用简单滑动平均
            logger.warning("数据不足,使用滑动平均进行预测")
            return self._fallback_predict(history_data)
        
        # 3. 模拟LSTM预测(生产环境调用真实模型)
        # 实际项目中使用: model.predict(X)
        last_sequence = normalized[-self.lookback:]
        predictions = self._simulate_lstm(last_sequence)
        
        # 4. 反归一化
        predictions = self.denormalize(predictions)
        
        # 5. 构建结果
        result = []
        base_time = datetime.now()
        for i, vol in enumerate(predictions):
            pred_time = base_time + timedelta(minutes=(i + 1) * 5)
            result.append({
                "prediction_time": pred_time.strftime("%Y-%m-%d %H:%M:%S"),
                "predicted_volume": max(0, int(round(vol))),
                "confidence": float(np.clip(0.75 + 0.03 * i, 0.7, 0.95))
            })
        
        logger.info(f"LSTM预测完成, 生成{len(result)}个预测点")
        return result
    
    def _simulate_lstm(self, last_sequence: np.ndarray) -> np.ndarray:
        """
        模拟LSTM预测(生产环境替换为真实模型推理)
        
        伪代码对应的真实LSTM前向传播:
            lstm_out, (hn, cn) = lstm(last_sequence)  # LSTM层
            lstm_out = dropout(lstm_out)               # Dropout
            predictions = linear(lstm_out)              # 全连接层
            return predictions
        """
        predictions = []
        current_seq = last_sequence.copy()
        
        for _ in range(self.forecast_steps):
            # 模拟LSTM特征提取
            # 实际: lstm_out = self.lstm(current_seq.reshape(1, -1, 1))
            features = np.mean(current_seq) * 0.9 + np.std(current_seq) * 0.1
            
            # 模拟全连接层输出
            next_val = features + np.random.normal(0, 0.02)
            predictions.append(next_val)
            
            # 滑动窗口更新
            current_seq = np.append(current_seq[1:], next_val)
        
        return np.array(predictions)
    
    def _fallback_predict(self, history_data: List[float]) -> List[Dict[str, Any]]:
        """
        数据不足时的后备预测方案
        使用加权滑动平均
        """
        data = np.array(history_data)
        weights = np.exp(np.linspace(0, 1, len(data)))
        weights /= weights.sum()
        
        base_value = np.sum(data * weights[-len(data):])
        
        result = []
        base_time = datetime.now()
        for i in range(self.forecast_steps):
            pred_time = base_time + timedelta(minutes=(i + 1) * 5)
            trend = np.sin(i * 0.5) * 50  # 模拟周期性波动
            result.append({
                "prediction_time": pred_time.strftime("%Y-%m-%d %H:%M:%S"),
                "predicted_volume": max(0, int(base_value + trend)),
                "confidence": 0.6
            })
        
        return result
    
    def batch_predict(self, intersections_data: Dict[str, List[float]]) -> Dict[str, Any]:
        """
        批量预测多个路口
        
        Args:
            intersections_data: 路口ID到历史数据的映射
            
        Returns:
            所有路口的预测结果
        """
        results = {}
        for intersection_id, history in intersections_data.items():
            results[intersection_id] = self.predict(history)
        return results


# ============ API服务接口 ============
def predict_api(history_json: str) -> str:
    """
    API调用接口
    
    Args:
        history_json: JSON格式的历史数据
        
    Returns:
        JSON格式的预测结果
    
    调用示例:
        curl -X POST http://localhost:5000/predict \\
            -H "Content-Type: application/json" \\
            -d '{"intersection_id":"INT-001","history":[200,210,230,...]}'
    """
    data = json.loads(history_json)
    predictor = TrafficLSTM()
    results = predictor.predict(data.get("history", []))
    return json.dumps({
        "intersection_id": data.get("intersection_id"),
        "timestamp": datetime.now().isoformat(),
        "predictions": results,
        "model_version": "LSTM-v1.0"
    }, ensure_ascii=False)


if __name__ == "__main__":
    # 测试示例
    test_history = [
        200, 210, 230, 250, 280, 300,
        320, 310, 290, 270, 260, 250
    ]
    
    predictor = TrafficLSTM()
    result = predictor.predict(test_history)
    print(json.dumps(result, indent=2, ensure_ascii=False))
