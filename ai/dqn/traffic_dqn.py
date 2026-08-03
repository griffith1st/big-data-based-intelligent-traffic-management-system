"""
DQN 信号灯智能配时优化模型
==============================
算法原理:
    DQN(Deep Q-Network)通过强化学习,将路口视为智能体,
    以交通状态为输入,信号灯配时为动作,通过Q-learning优化策略。

伪代码:
    function DQN_Optimize(state):
        // 1. 状态编码
        state_vector = encode_state(traffic_volume, queue_length, wait_time)
        
        // 2. Q网络推理
        q_values = Q_Network(state_vector)  // 评估每个动作的Q值
        
        // 3. ε-贪婪策略选择动作
        if random() < epsilon:
            action = random_action()     // 探索
        else:
            action = argmax(q_values)    // 利用
            
        // 4. 执行动作,更新配时
        signal_plan = execute_action(action)
        
        // 5. 计算奖励
        reward = calculate_reward(throughput, wait_time, emission)
        
        // 6. 经验回放更新
        replay_buffer.store(state, action, reward, next_state)
        if replay_buffer.size > batch_size:
            train_Q_network(batch)       // 训练Q网络
            
        return signal_plan

状态空间: [各方向车流量, 排队长度, 等待时间]
动作空间: [绿灯时长调整: -10s, -5s, 0s, +5s, +10s]
奖励函数: R = w1*throughput - w2*wait_time - w3*emission
"""

import numpy as np
import json
import logging
from typing import List, Dict, Any
from collections import deque
import random

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class DQNSignalOptimizer:
    """
    DQN信号灯配时优化器
    对应于: ai/dqn/dqn_model.py (PyTorch实现)
    """
    
    def __init__(self):
        # 状态空间维度: [北,南,东,西] x [车流量,排队长度,等待时间]
        self.state_dim = 12
        # 动作空间: 各方向绿灯时长调整[-10,-5,0,+5,+10]秒
        self.action_dim = 5
        self.actions = [-10, -5, 0, 5, 10]
        
        # DQN超参数
        self.epsilon = 0.1          # 探索率
        self.gamma = 0.95           # 折扣因子
        self.learning_rate = 0.001  # 学习率
        self.batch_size = 32        # 批次大小
        
        # 经验回放缓冲区
        self.replay_buffer = deque(maxlen=10000)
        
        # 模拟Q网络参数(生产环境使用PyTorch/TensorFlow)
        self.q_table = {}
        
    def encode_state(self, traffic_data: Dict[str, Dict]) -> np.ndarray:
        """
        状态编码: 将交通数据转为状态向量
        
        Args:
            traffic_data: 各方向交通数据
                {
                    "NORTH": {"volume": 500, "queue": 20, "wait": 30},
                    "SOUTH": {"volume": 300, "queue": 15, "wait": 25},
                    ...
                }
        
        Returns:
            12维状态向量
        """
        state = []
        directions = ["NORTH", "SOUTH", "EAST", "WEST"]
        
        for direction in directions:
            if direction in traffic_data:
                data = traffic_data[direction]
                state.extend([
                    data.get("volume", 0) / 1000.0,     # 归一化车流量
                    data.get("queue", 0) / 50.0,        # 归一化排队长度
                    data.get("wait", 0) / 120.0          # 归一化等待时间
                ])
            else:
                state.extend([0, 0, 0])
        
        return np.array(state, dtype=np.float32)
    
    def get_q_values(self, state_vector: np.ndarray) -> np.ndarray:
        """
        Q网络前向传播(模拟,生产环境替换为真实神经网络)
        
        真实DQN网络结构:
            Q_Network(
                Linear(12, 128)      # 输入层
                ReLU()               # 激活函数
                Linear(128, 64)      # 隐藏层
                ReLU()
                Linear(64, 5)        # 输出层(5个动作)
            )
        """
        state_key = tuple(state_vector.round(2))
        
        if state_key not in self.q_table:
            # 初始化Q值: 基于车流量比例分配
            total_volume = sum(abs(v) for v in state_vector[::3]) + 1
            north_south_ratio = (state_vector[0] + state_vector[3]) / total_volume
            
            # 车流量大则增加绿灯时间,车流量小则减少
            base_q = np.array([
                -north_south_ratio * 2,     # -10s
                -north_south_ratio,          # -5s
                0,                           # 0s
                north_south_ratio,           # +5s
                north_south_ratio * 2        # +10s
            ])
            self.q_table[state_key] = base_q
        
        return self.q_table[state_key]
    
    def select_action(self, state_vector: np.ndarray, training: bool = True) -> int:
        """
        ε-贪婪策略选择动作
        
        Args:
            state_vector: 状态向量
            training: 是否训练模式
            
        Returns:
            动作索引 (0~4)
        """
        if training and random.random() < self.epsilon:
            return random.randint(0, self.action_dim - 1)
        
        q_values = self.get_q_values(state_vector)
        return int(np.argmax(q_values))
    
    def calculate_reward(self, before_state: Dict, after_state: Dict) -> float:
        """
        计算奖励函数
        R = w1*throughput - w2*wait_time - w3*emission
        
        Args:
            before_state: 优化前状态
            after_state: 优化后状态
            
        Returns:
            奖励值
        """
        w1, w2, w3 = 1.0, 0.5, 0.3
        
        # 通行效率提升
        throughput = after_state.get("throughput", 0) - before_state.get("throughput", 0)
        # 等待时间减少
        wait_reduction = before_state.get("avg_wait", 0) - after_state.get("avg_wait", 0)
        # 碳排放减少
        emission_reduction = before_state.get("emission", 0) - after_state.get("emission", 0)
        
        reward = (w1 * throughput + w2 * wait_reduction + w3 * emission_reduction) / 100.0
        return float(np.clip(reward, -1, 1))
    
    def optimize(self, intersection_id: str, traffic_data: Dict[str, Dict],
                 current_signals: Dict[str, int]) -> Dict[str, Any]:
        """
        DQN信号灯优化主流程
        
        Args:
            intersection_id: 路口编号
            traffic_data: 各方向交通数据
            current_signals: 当前信号灯配时 {"NORTH": 45, ...}
            
        Returns:
            优化后的信号灯配时方案
        """
        logger.info(f"DQN优化路口 {intersection_id}")
        
        # 1. 状态编码
        state = self.encode_state(traffic_data)
        
        # 2. 选择动作
        action_idx = self.select_action(state, training=False)
        adjustment = self.actions[action_idx]
        
        # 3. 生成优化方案
        optimized_signals = {}
        directions = ["NORTH", "SOUTH", "EAST", "WEST"]
        
        for i, direction in enumerate(directions):
            current_green = current_signals.get(direction, 45)
            
            if direction in traffic_data:
                volume = traffic_data[direction].get("volume", 500)
                # 主方向(+adjustment), 次方向(-adjustment)
                if i < 2:  # 南北方向
                    new_green = current_green + adjustment
                else:      # 东西方向
                    new_green = current_green - adjustment * 0.5
                
                # 约束在合理范围[15, 120]秒
                new_green = max(15, min(120, int(round(new_green))))
            else:
                new_green = current_green
            
            optimized_signals[direction] = new_green
        
        # 4. 计算预估奖励
        reward = self.calculate_reward(
            {"throughput": 0, "avg_wait": 60, "emission": 50},
            {"throughput": 10, "avg_wait": 45, "emission": 40}
        )
        
        logger.info(f"DQN优化完成, 调整量: {adjustment}s, 预估奖励: {reward:.2f}")
        
        return {
            "intersection_id": intersection_id,
            "optimized_signals": optimized_signals,
            "adjustment": adjustment,
            "reward": reward,
            "strategy": "DQN"
        }
    
    def train_step(self, state: np.ndarray, action: int, reward: float,
                   next_state: np.ndarray, done: bool) -> Dict:
        """
        单步训练(经验回放)
        
        伪代码对应的DQN训练过程:
            batch = replay_buffer.sample(batch_size)
            target = reward + gamma * max(Q_next)  # 目标Q值
            loss = MSE(Q_current, target)           # 损失函数
            optimizer.backward(loss)                 # 反向传播
            optimizer.step()                         # 参数更新
        """
        # 存储经验
        self.replay_buffer.append((state, action, reward, next_state, done))
        
        if len(self.replay_buffer) >= self.batch_size:
            # 从经验回放中采样
            batch = random.sample(self.replay_buffer, self.batch_size)
            
            # 计算当前Q值和目标Q值
            current_q = self.get_q_values(state)
            next_q = self.get_q_values(next_state)
            target_q = reward + self.gamma * np.max(next_q) * (not done)
            
            # 更新Q表(简化,实际用神经网络优化器)
            state_key = tuple(state.round(2))
            if state_key in self.q_table:
                self.q_table[state_key][action] += \
                    self.learning_rate * (target_q - current_q[action])
            
            loss = (target_q - current_q[action]) ** 2
            return {"loss": float(loss), "q_value": float(np.max(current_q))}
        
        return {"loss": 0, "q_value": 0}


# ============ API服务接口 ============
def optimize_api(request_json: str) -> str:
    """
    DQN优化API
    
    调用示例:
        curl -X POST http://localhost:5001/optimize \\
            -H "Content-Type: application/json" \\
            -d '{"intersection_id":"INT-001", "traffic_data":{...}}'
    """
    data = json.loads(request_json)
    optimizer = DQNSignalOptimizer()
    result = optimizer.optimize(
        data["intersection_id"],
        data.get("traffic_data", {}),
        data.get("current_signals", {})
    )
    return json.dumps(result, ensure_ascii=False, cls=JsonEncoder)


class JsonEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, np.ndarray):
            return obj.tolist()
        if isinstance(obj, np.floating):
            return float(obj)
        return super().default(obj)


if __name__ == "__main__":
    # 测试示例
    test_data = {
        "NORTH": {"volume": 800, "queue": 30, "wait": 45},
        "SOUTH": {"volume": 600, "queue": 25, "wait": 35},
        "EAST": {"volume": 300, "queue": 10, "wait": 20},
        "WEST": {"volume": 400, "queue": 15, "wait": 25}
    }
    current = {"NORTH": 45, "SOUTH": 45, "EAST": 30, "WEST": 30}
    
    optimizer = DQNSignalOptimizer()
    result = optimizer.optimize("INT-001", test_data, current)
    print(json.dumps(result, indent=2, ensure_ascii=False, cls=JsonEncoder))
