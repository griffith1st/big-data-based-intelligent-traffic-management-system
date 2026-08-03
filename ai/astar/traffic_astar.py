"""
A* 低碳最优路径规划算法
============================
算法原理:
    A*算法通过启发式搜索,结合实际距离(g(n))和估计距离(h(n)),
    在路网图中寻找从起点到终点的最优路径。
    引入碳排放因子,支持低碳路径规划。

伪代码:
    function AStar(start, end, graph, strategy):
        open_set = PriorityQueue()    // 待探索节点
        open_set.add(start, 0)
        came_from = {}                // 路径回溯
        g_score = {start: 0}          // 实际代价
        f_score = {start: h(start)}   // 总代价 = g + h
        
        while open_set is not empty:
            current = open_set.pop()
            
            if current == end:
                return reconstruct_path(came_from, current)
            
            for neighbor in graph.neighbors(current):
                // 根据策略计算代价
                if strategy == "GREENEST":
                    cost = distance * carbon_factor  // 低碳代价
                elif strategy == "FASTEST":
                    cost = distance / speed          // 时间代价
                else:
                    cost = distance                  // 距离代价
                
                tentative_g = g_score[current] + cost
                
                if tentative_g < g_score.get(neighbor, INF):
                    came_from[neighbor] = current
                    g_score[neighbor] = tentative_g
                    f_score[neighbor] = g_score[neighbor] + h(neighbor)
                    
                    if neighbor not in open_set:
                        open_set.add(neighbor, f_score[neighbor])
        
        return None  // 无可行路径

启发函数: 曼哈顿距离 * 自适应系数
代价函数: 
    - 最短路径: g = distance
    - 最快路径: g = distance / avg_speed  
    - 低碳路径: g = distance * carbon_emission_factor
"""

import numpy as np
import json
import math
import logging
from heapq import heappush, heappop
from typing import List, Tuple, Dict, Any, Optional

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class Node:
    """路网节点(路口)"""
    
    def __init__(self, node_id: str, lng: float, lat: float):
        self.id = node_id
        self.lng = lng
        self.lat = lat
        
    def __repr__(self):
        return f"Node({self.id})"
    
    def __lt__(self, other):
        return self.id < other.id


class Edge:
    """路段(连接两个路口的道路)"""
    
    def __init__(self, start_id: str, end_id: str, distance: float,
                 avg_speed: float = 40, congestion_level: int = 0):
        self.start_id = start_id
        self.end_id = end_id
        self.distance = distance          # 距离(米)
        self.avg_speed = avg_speed        # 平均速度(km/h)
        self.congestion_level = congestion_level  # 拥堵等级(0-3)
        
    @property
    def travel_time(self) -> float:
        """行驶时间(分钟)"""
        return (self.distance / 1000) / max(self.avg_speed, 1) * 60
    
    @property
    def carbon_emission(self) -> float:
        """碳排放量(kg) - 基于速度和距离估算"""
        # 低速/拥堵时碳排放更高
        base_emission = self.distance * 0.00015  # 基础排放 kg/m
        congestion_factor = 1 + self.congestion_level * 0.3
        return base_emission * congestion_factor


class TrafficGraph:
    """路网图"""
    
    def __init__(self):
        self.nodes: Dict[str, Node] = {}
        self.edges: Dict[str, List[Edge]] = {}  # 邻接表
    
    def add_node(self, node_id: str, lng: float, lat: float):
        """添加路口节点"""
        self.nodes[node_id] = Node(node_id, lng, lat)
        if node_id not in self.edges:
            self.edges[node_id] = []
    
    def add_edge(self, start_id: str, end_id: str, distance: float,
                 avg_speed: float = 40, congestion: int = 0):
        """添加路段(双向)"""
        self.edges[start_id].append(Edge(start_id, end_id, distance, avg_speed, congestion))
        self.edges[end_id].append(Edge(end_id, start_id, distance, avg_speed, congestion))
    
    def get_neighbors(self, node_id: str) -> List[Edge]:
        """获取相邻路段"""
        return self.edges.get(node_id, [])
    
    def get_node(self, node_id: str) -> Optional[Node]:
        """获取节点"""
        return self.nodes.get(node_id)


class AStarPlanner:
    """
    A*路径规划器
    
    支持三种策略:
    - SHORTEST: 最短距离
    - FASTEST:  最短时间
    - GREENEST: 最低碳排放
    """
    
    def __init__(self, graph: TrafficGraph):
        self.graph = graph
        self.strategies = {
            "SHORTEST": self._shortest_cost,
            "FASTEST": self._fastest_cost,
            "GREENEST": self._greenest_cost
        }
    
    def heuristic(self, node_id: str, target_id: str) -> float:
        """
        启发式函数: 曼哈顿距离
        
        h(n) = |n.lat - target.lat| + |n.lng - target.lng|
        """
        node = self.graph.get_node(node_id)
        target = self.graph.get_node(target_id)
        
        if not node or not target:
            return float('inf')
        
        # 将经纬度差转为近似米数
        lat_diff = (node.lat - target.lat) * 111000
        lng_diff = (node.lng - target.lng) * 111000 * \
                   math.cos(math.radians((node.lat + target.lat) / 2))
        
        return math.sqrt(lat_diff ** 2 + lng_diff ** 2)
    
    def _shortest_cost(self, edge: Edge) -> float:
        """最短路径代价 = 距离"""
        return edge.distance
    
    def _fastest_cost(self, edge: Edge) -> float:
        """最快路径代价 = 时间(分钟)"""
        return edge.travel_time
    
    def _greenest_cost(self, edge: Edge) -> float:
        """低碳路径代价 = 碳排放量"""
        return edge.carbon_emission
    
    def plan(self, start_id: str, end_id: str,
             strategy: str = "SHORTEST") -> Dict[str, Any]:
        """
        A*路径规划主方法
        
        Args:
            start_id: 起点路口ID
            end_id: 终点路口ID
            strategy: 规划策略
            
        Returns:
            路径规划结果
        """
        logger.info(f"A*路径规划: {start_id} -> {end_id}, 策略={strategy}")
        
        if start_id not in self.graph.nodes or end_id not in self.graph.nodes:
            return {"error": "起点或终点不存在", "route": None}
        
        if strategy not in self.strategies:
            strategy = "SHORTEST"
        
        cost_func = self.strategies[strategy]
        
        # A*算法核心
        open_set = []  # 优先队列: (f_score, node_id)
        heappush(open_set, (0, start_id))
        
        came_from: Dict[str, str] = {}
        g_score: Dict[str, float] = {start_id: 0}
        f_score: Dict[str, float] = {
            start_id: self.heuristic(start_id, end_id)
        }
        
        closed_set = set()
        
        while open_set:
            _, current = heappop(open_set)
            
            if current in closed_set:
                continue
                
            if current == end_id:
                return self._build_result(start_id, end_id,
                                          came_from, g_score, strategy)
            
            closed_set.add(current)
            
            for edge in self.graph.get_neighbors(current):
                neighbor = edge.end_id
                
                if neighbor in closed_set:
                    continue
                
                # 计算临时g值
                tentative_g = g_score[current] + cost_func(edge)
                
                if tentative_g < g_score.get(neighbor, float('inf')):
                    came_from[neighbor] = current
                    g_score[neighbor] = tentative_g
                    f = tentative_g + self.heuristic(neighbor, end_id)
                    f_score[neighbor] = f
                    heappush(open_set, (f, neighbor))
        
        return {"error": "无法找到可行路径", "route": None}
    
    def _build_result(self, start_id: str, end_id: str,
                      came_from: Dict[str, str], g_score: Dict[str, float],
                      strategy: str) -> Dict[str, Any]:
        """构建规划结果"""
        # 重建路径
        path = self._reconstruct_path(came_from, start_id, end_id)
        
        # 计算总距离, 时间, 碳排放
        total_distance = 0
        total_time = 0
        total_carbon = 0
        route_points = []
        
        for i in range(len(path) - 1):
            for edge in self.graph.get_neighbors(path[i]):
                if edge.end_id == path[i + 1]:
                    total_distance += edge.distance
                    total_time += edge.travel_time
                    total_carbon += edge.carbon_emission
                    break
        
        # 生成路径坐标点
        for node_id in path:
            node = self.graph.get_node(node_id)
            if node:
                route_points.append({
                    "lng": round(node.lng, 6),
                    "lat": round(node.lat, 6)
                })
        
        logger.info(f"A*规划完成: 距离={total_distance:.0f}m, "
                   f"时间={total_time:.0f}min, 碳排={total_carbon:.2f}kg")
        
        return {
            "start_id": start_id,
            "end_id": end_id,
            "strategy": strategy,
            "path": path,
            "total_distance": round(total_distance, 2),
            "estimated_time": round(total_time),
            "carbon_emission": round(total_carbon, 3),
            "route_points": route_points,
            "node_count": len(path)
        }
    
    def _reconstruct_path(self, came_from: Dict[str, str],
                          start: str, end: str) -> List[str]:
        """回溯重建路径"""
        path = []
        current = end
        
        while current != start:
            path.append(current)
            current = came_from.get(current)
            if current is None:
                return []
        
        path.append(start)
        path.reverse()
        return path


def build_sample_graph() -> TrafficGraph:
    """构建示例路网图"""
    graph = TrafficGraph()
    
    # 添加路口节点(模拟城市路网)
    nodes = [
        ("INT-001", 116.397, 39.908),  # 天安门
        ("INT-002", 116.410, 39.908),  # 东单
        ("INT-003", 116.420, 39.908),  # 建国门
        ("INT-004", 116.397, 39.920),  # 地安门
        ("INT-005", 116.410, 39.920),  # 鼓楼
        ("INT-006", 116.420, 39.920),  # 雍和宫
        ("INT-007", 116.397, 39.898),  # 前门
        ("INT-008", 116.410, 39.898),  # 崇文门
        ("INT-009", 116.420, 39.898),  # 东便门
    ]
    
    for nid, lng, lat in nodes:
        graph.add_node(nid, lng, lat)
    
    # 添加路段
    edges = [
        ("INT-001", "INT-002", 800, 35, 1),
        ("INT-002", "INT-003", 600, 40, 0),
        ("INT-001", "INT-004", 800, 30, 2),
        ("INT-002", "INT-005", 700, 45, 0),
        ("INT-003", "INT-006", 700, 50, 0),
        ("INT-004", "INT-005", 600, 35, 1),
        ("INT-005", "INT-006", 600, 40, 0),
        ("INT-001", "INT-007", 700, 40, 0),
        ("INT-002", "INT-008", 700, 45, 0),
        ("INT-003", "INT-009", 800, 50, 0),
        ("INT-004", "INT-007", 800, 30, 2),
        ("INT-005", "INT-008", 800, 40, 0),
        ("INT-006", "INT-009", 700, 45, 0),
        ("INT-007", "INT-008", 600, 35, 1),
        ("INT-008", "INT-009", 600, 40, 0),
    ]
    
    for sid, eid, dist, speed, cong in edges:
        graph.add_edge(sid, eid, dist, speed, cong)
    
    return graph


# ============ API服务接口 ============
def plan_api(request_json: str) -> str:
    """
    A*路径规划API
    
    调用示例:
        curl -X POST http://localhost:5002/plan \\
            -H "Content-Type: application/json" \\
            -d '{"start":"INT-001","end":"INT-009","strategy":"GREENEST"}'
    """
    data = json.loads(request_json)
    graph = build_sample_graph()
    planner = AStarPlanner(graph)
    
    result = planner.plan(
        data.get("start", "INT-001"),
        data.get("end", "INT-009"),
        data.get("strategy", "SHORTEST")
    )
    
    return json.dumps(result, ensure_ascii=False)


if __name__ == "__main__":
    # 测试示例
    graph = build_sample_graph()
    planner = AStarPlanner(graph)
    
    print("=== 最短路径 ===")
    result1 = planner.plan("INT-001", "INT-009", "SHORTEST")
    print(json.dumps(result1, indent=2, ensure_ascii=False))
    
    print("\n=== 低碳路径 ===")
    result2 = planner.plan("INT-001", "INT-009", "GREENEST")
    print(json.dumps(result2, indent=2, ensure_ascii=False))
    
    print("\n=== 最快路径 ===")
    result3 = planner.plan("INT-001", "INT-009", "FASTEST")
    print(json.dumps(result3, indent=2, ensure_ascii=False))
