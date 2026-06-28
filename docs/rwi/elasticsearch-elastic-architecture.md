# Elasticsearch 弹性架构调研文档

## 1. 背景与目标

Elasticsearch 传统部署模式为固定节点数的静态集群，存在以下痛点：

- **资源浪费**：业务低峰期集群资源利用率不足 20%
- **扩容滞后**：大促/突发流量场景下，扩容需要数十分钟甚至数小时
- **运维成本高**：分片迁移、数据再平衡需要人工介入
- **存储与计算耦合**：无法独立扩展存储或计算能力

弹性架构的目标是实现 **按需分配、自动伸缩、秒级响应** 的 Elasticsearch 集群管理能力。

---

## 2. 核心设计方案

### 2.1 Autoscaling（自动伸缩）

#### 2.1.1 Elastic 官方 Autoscaling

Elastic 从 7.11 版本引入 Autoscaling API，核心设计：

```
┌─────────────────────────────────────────────┐
│              Autoscaling Policy              │
├─────────────────────────────────────────────┤
│  Decider（决策器）                            │
│  ├── Reactive Storage Decider               │
│  ├── Proactive Storage Decider              │
│  ├── Frozen Shards Decider                  │
│  ├── Machine Learning Decider               │
│  └── Fixed Decider                          │
├─────────────────────────────────────────────┤
│  输出：required_capacity                      │
│  ├── node 数量                               │
│  ├── 每 node 内存/磁盘                       │
│  └── 总集群容量                              │
└─────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────┐
│       Orchestrator（编排器）                  │
│  ECE / ECK / Elastic Cloud                  │
│  根据 required_capacity 执行扩缩容           │
└─────────────────────────────────────────────┘
```

**关键机制：**

| 组件 | 职责 |
|------|------|
| Reactive Storage Decider | 响应式：当前磁盘使用超阈值时触发扩容 |
| Proactive Storage Decider | 预测式：基于索引增长速率预测未来存储需求 |
| Frozen Shards Decider | 冷冻层分片计数决定内存需求 |
| ML Decider | ML 任务资源需求评估 |

**工作流程：**

1. 各 Decider 独立计算所需容量
2. 取所有 Decider 结果的最大值
3. 编排层接收容量需求，执行实际扩缩容
4. 支持设置 `min/max` 边界防止过度伸缩

#### 2.1.2 自研 Autoscaling 方案

对于私有化部署场景，可设计如下自研方案：

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Metrics     │────▶│  Controller  │────▶│  Executor    │
│  Collector   │     │  (决策引擎)   │     │  (执行引擎)   │
└──────────────┘     └──────────────┘     └──────────────┘
       │                     │                     │
  采集指标：            决策逻辑：            执行动作：
  - CPU/Mem 使用率     - 阈值策略           - K8s Pod 扩缩
  - 磁盘使用率         - 预测策略           - 节点加入/移除
  - 搜索延迟          - 时间窗口策略        - 分片迁移
  - 索引吞吐          - 自定义规则          - 路由调整
  - 队列积压
```

**核心指标维度：**

```json
{
  "scaling_metrics": {
    "compute": ["cpu_usage", "heap_usage", "search_latency_p99", "indexing_rate"],
    "storage": ["disk_usage_percent", "disk_growth_rate", "shard_size"],
    "queue": ["search_queue_size", "bulk_queue_rejected", "thread_pool_queue"]
  },
  "scaling_rules": {
    "scale_out": "cpu > 70% AND duration > 5min",
    "scale_in": "cpu < 30% AND duration > 15min AND no_active_relocation",
    "cooldown": "10min"
  }
}
```

---

### 2.2 存算分离架构

#### 2.2.1 传统架构 vs 存算分离

```
传统架构（存算一体）：
┌─────────────────────────┐
│  ES Node                │
│  ├── CPU/Memory (计算)   │
│  ├── Local SSD (存储)    │  ← 耦合：扩存储必须扩计算
│  └── Lucene Segments    │
└─────────────────────────┘

存算分离架构：
┌──────────────────┐          ┌──────────────────┐
│  Compute Layer   │          │  Storage Layer   │
│  ├── Query Node  │  ◀─────▶ │  ├── S3/OSS      │
│  ├── Index Node  │          │  ├── HDFS         │
│  └── Coord Node  │          │  └── 共享存储      │
└──────────────────┘          └──────────────────┘
        │
   ┌────┴─────┐
   │  Cache   │  ← 本地 SSD 作为热数据缓存
   │  Layer   │
   └──────────┘
```

#### 2.2.2 Elasticsearch Searchable Snapshots（可搜索快照）

Elastic 从 7.12 引入的存算分离核心能力：

```
数据层级架构：
┌─────────┐  全量副本     ┌─────────┐  无副本+缓存   ┌─────────┐  按需加载
│  Hot    │  ──────────▶  │  Warm   │  ──────────▶  │  Cold   │  ───────▶  Frozen
│  Tier   │  本地SSD      │  Tier   │  本地存储      │  Tier   │           远程快照
└─────────┘               └─────────┘               └─────────┘
   全性能                    降低副本成本              存算分离              纯远程存储
```

**Frozen Tier 实现原理：**

1. 数据完全存储在远程对象存储（S3/GCS/Azure Blob）
2. 本地仅保留元数据 + LRU 缓存
3. 查询时按需从远程加载 Lucene Segment
4. 通过 `shared_cache` 实现本地缓存管理

```yaml
# frozen tier 节点配置示例
node.roles: ["data_frozen"]
xpack.searchable.snapshot.shared_cache.size: 90%  # 本地 SSD 的 90% 作为缓存
xpack.searchable.snapshot.shared_cache.size.max_headroom: 100GB
```

#### 2.2.3 自研存算分离方案设计

```
┌───────────────────────────────────────────────────────────────┐
│                      Coordinator Layer                         │
│  负责查询路由、聚合、结果合并                                    │
└───────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  Compute Node   │ │  Compute Node   │ │  Compute Node   │
│  (无状态)        │ │  (无状态)        │ │  (无状态)        │
│  - Query Engine │ │  - Query Engine │ │  - Query Engine │
│  - Local Cache  │ │  - Local Cache  │ │  - Local Cache  │
└─────────────────┘ └─────────────────┘ └─────────────────┘
              │               │               │
              └───────────────┼───────────────┘
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                      Storage Layer                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────────────────────┐       │
│  │ Segment │  │ Segment │  │ Metadata Service        │       │
│  │ Store   │  │ Store   │  │ (segment路由/版本管理)   │       │
│  │ (S3)    │  │ (HDFS)  │  └─────────────────────────┘       │
│  └─────────┘  └─────────┘                                    │
└───────────────────────────────────────────────────────────────┘
```

**核心设计要点：**

| 维度 | 设计 |
|------|------|
| 计算节点 | 无状态，可快速水平扩展，启动即服务 |
| 存储层 | 对象存储（S3/OSS/HDFS），按容量计费 |
| 缓存策略 | 多级缓存：L1(内存) + L2(本地SSD) + L3(远程存储) |
| Segment 管理 | 独立 Metadata Service 管理 segment 到节点的映射 |
| 写入路径 | 写入节点本地 → flush → 上传到远程存储 → 通知其他节点 |

---

### 2.3 动态资源分配与节点扩缩容

#### 2.3.1 基于 Kubernetes 的弹性方案

```yaml
# ECK (Elastic Cloud on Kubernetes) 弹性配置示例
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: elastic-cluster
spec:
  version: 8.x
  nodeSets:
  - name: hot
    count: 3          # 固定热节点
    config:
      node.roles: ["master", "data_hot", "ingest"]
    volumeClaimTemplates:
    - metadata:
        name: elasticsearch-data
      spec:
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 500Gi
  - name: warm
    count: 2          # 可弹性伸缩
    config:
      node.roles: ["data_warm"]
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          resources:
            requests:
              memory: 16Gi
              cpu: 4
            limits:
              memory: 16Gi
              cpu: 8
```

#### 2.3.2 扩缩容流程

```
扩容流程：
┌────────┐    ┌────────────┐    ┌───────────┐    ┌────────────┐    ┌──────────┐
│ 触发   │───▶│ 创建新节点  │───▶│ 加入集群   │───▶│ 分片迁移    │───▶│ 负载均衡  │
│ 扩容   │    │ (K8s Pod)  │    │ (发现机制) │    │ (rebalance)│    │ 完成     │
└────────┘    └────────────┘    └───────────┘    └────────────┘    └──────────┘
                                                        │
                                               耗时最长的环节
                                               (数据量决定时间)

缩容流程：
┌────────┐    ┌────────────┐    ┌───────────┐    ┌────────────┐    ┌──────────┐
│ 触发   │───▶│ 标记节点   │───▶│ 迁移分片   │───▶│ 移除节点    │───▶│ 释放资源  │
│ 缩容   │    │ exclude    │    │ 至其他节点 │    │ 退出集群    │    │ 完成     │
└────────┘    └────────────┘    └───────────┘    └────────────┘    └──────────┘
```

**关键配置：**

```json
PUT _cluster/settings
{
  "transient": {
    "cluster.routing.allocation.exclude._name": "node-to-remove",
    "cluster.routing.allocation.cluster_concurrent_rebalance": 4,
    "indices.recovery.max_bytes_per_sec": "200mb"
  }
}
```

---

### 2.4 分片策略与数据迁移

#### 2.4.1 弹性分片策略

| 策略 | 描述 | 适用场景 |
|------|------|---------|
| 固定分片 | 创建索引时确定分片数 | 数据量稳定的业务 |
| 时间滚动索引 | 按时间（天/周）创建新索引 | 日志类、时序数据 |
| 动态分片 (Split/Shrink) | 运行时调整分片数 | 数据量变化大的业务 |
| 自动分片 (Logsdb) | ES 8.x 自动管理分片数 | 日志场景 |

#### 2.4.2 数据迁移优化

```
优化策略：
┌─────────────────────────────────────────────────────────┐
│  1. 并行恢复                                            │
│     indices.recovery.max_concurrent_operations: 4       │
│     cluster.routing.allocation.node_concurrent_         │
│     incoming_recoveries: 4                              │
├─────────────────────────────────────────────────────────┤
│  2. 限流控制                                            │
│     indices.recovery.max_bytes_per_sec: 200mb           │
│     避免迁移影响正常查询                                 │
├─────────────────────────────────────────────────────────┤
│  3. 感知迁移                                            │
│     基于分片大小排序迁移，优先迁移小分片                   │
│     减少节点间数据不均衡的时间窗口                        │
├─────────────────────────────────────────────────────────┤
│  4. 增量迁移                                            │
│     利用 Segment 文件级别的 file-based recovery          │
│     仅传输差异 segment，减少传输量                       │
└─────────────────────────────────────────────────────────┘
```

---

### 2.5 热温冷分层架构（ILM）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Index Lifecycle Management (ILM)                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────┐  rollover  ┌─────────┐  migrate  ┌─────────┐  freeze     │
│  │   Hot   │ ─────────▶ │  Warm   │ ────────▶ │  Cold   │ ────────▶   │
│  │  Tier   │            │  Tier   │           │  Tier   │             │
│  └─────────┘            └─────────┘           └─────────┘             │
│   高性能SSD               HDD/大容量            对象存储                 │
│   全副本                  减少副本              可搜索快照                │
│   主动索引                只读                  按需加载                  │
│                                                                         │
│  ┌─────────┐  delete                                                   │
│  │ Frozen  │ ────────▶  Delete Phase                                   │
│  │  Tier   │                                                           │
│  └─────────┘                                                           │
│   纯远程存储                                                            │
│   极低成本                                                              │
│   秒级延迟可接受                                                        │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 2.5.2 ILM 的局限性：非时序数据无法整索引分层

ILM 以索引为最小操作单位，只适用于可按时间切分索引的数据。以下场景无法直接使用 ILM：

| 场景 | 问题 |
|------|------|
| 商品索引 | 同一索引里，爆款商品被高频访问，长尾商品几乎无查询 |
| 用户索引 | 活跃用户和沉默用户混在同一索引中 |
| 订单索引（按用户分片）| 同一分片内既有近期订单也有历史订单 |
| 知识库/文档索引 | 文档热度与创建时间无强相关 |

**核心矛盾：** 数据冷热是文档级别的，ILM 只能做索引级别的搬迁。

#### 2.5.3 文档级分层方案

针对非时序数据的冷热分离，需要在索引之下做更细粒度的分层：

```
方案一：业务层拆分（推荐，复杂度最低）
┌──────────────────────────────────────────────────────────────┐
│  将实体索引按业务维度拆分为多个子索引，映射到不同层             │
│                                                              │
│  商品场景：                                                   │
│  ├── products_active   (Hot Tier)  ← 近30天有交易/浏览的商品  │
│  ├── products_inactive (Warm Tier) ← 无交易但仍在架的商品      │
│  └── products_archived (Cold Tier) ← 下架超90天的商品         │
│                                                              │
│  定时任务（T+1/实时）根据业务规则 reindex 文档到对应索引        │
│  查询时通过 alias 或 coord node 路由到对应索引                 │
└──────────────────────────────────────────────────────────────┘

方案二：自定义路由 + 分片级分层
┌──────────────────────────────────────────────────────────────┐
│  利用 index.routing.allocation.require 做分片级冷热控制        │
│                                                              │
│  设计：                                                       │
│  ├── 索引拆分为 hot_shards + cold_shards                      │
│  ├── 自定义 routing 规则将热文档路由到 hot_shards             │
│  ├── hot_shards 分配到 SSD 节点                              │
│  └── cold_shards 分配到 HDD/远程存储节点                      │
│                                                              │
│  局限：需要业务侧感知路由，且文档冷热变化时需 reindex          │
└──────────────────────────────────────────────────────────────┘

方案三：Segment 级别冷热分离（深度定制）
┌──────────────────────────────────────────────────────────────┐
│  修改 ES 内核，支持 Segment 级别的存储分层                     │
│                                                              │
│  原理：                                                       │
│  ├── Lucene 的 Segment 是不可变的最小存储单元                  │
│  ├── Forcemerge 后，冷数据集中到老 Segment                    │
│  ├── 将老 Segment 迁移到远程存储，新 Segment 留本地            │
│  └── 查询时对远程 Segment 做延迟加载                          │
│                                                              │
│  优势：对业务透明，无需拆索引                                  │
│  劣势：需要内核改造，Segment merge 策略复杂                    │
└──────────────────────────────────────────────────────────────┘

方案四：基于文档时间戳的 Sort + 查询优化
┌──────────────────────────────────────────────────────────────┐
│  不做物理分层，通过查询层优化降低冷数据的资源消耗              │
│                                                              │
│  设计：                                                       │
│  ├── index.sort.field 设置为业务热度/时间字段                  │
│  ├── 热数据集中在前部 Segment block                           │
│  ├── 查询加 filter 或 early termination 避免扫描冷数据        │
│  └── 配合 index.blocks.read_only 冻结不活跃 Segment          │
│                                                              │
│  优势：零改造成本，立即可用                                    │
│  局限：只优化了查询，存储层仍然混合                            │
└──────────────────────────────────────────────────────────────┘
```

**方案选型建议：**

| 方案 | 适用场景 | 实施成本 | 存储收益 | 查询影响 |
|------|---------|---------|---------|---------|
| 业务层拆分 | 冷热边界清晰（如活跃/下架） | 中 | 高 | 需 alias 路由 |
| 分片级分层 | 已有自定义 routing 的场景 | 中 | 中 | 对业务有侵入 |
| Segment 级分离 | 有内核研发能力 | 极高 | 高 | 对业务透明 |
| Sort + 查询优化 | 快速落地、降低查询成本 | 低 | 低 | 查询需配合 |

实际落地中，**方案一 + 方案四组合**是性价比最高的选择：业务层按明确规则拆分索引做物理隔离，索引内部通过 sort 优化减少冷数据扫描。

#### 2.5.4 Segment 级分层实现方案（深度设计）

##### 核心思路

Lucene 的 Segment 是不可变的最小存储单元。一个分片由多个 Segment 组成，Segment 一旦写入就不再修改（只会被 merge 合并）。利用这个不可变性，可以将不同 Segment 存储到不同介质：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        一个 Shard 的 Segment 分布                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  本地 SSD（热 Segment）                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                                │
│  │ Seg-007  │ │ Seg-008  │ │ Seg-009  │  ← 最近写入/刚 merge 的 segment │
│  │ 50MB     │ │ 120MB    │ │ 30MB     │  ← 包含大量活跃文档             │
│  └──────────┘ └──────────┘ └──────────┘                                │
│                                                                         │
│  远程对象存储（冷 Segment）                                               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐                   │
│  │ Seg-001  │ │ Seg-002  │ │ Seg-003  │ │ Seg-004  │                   │
│  │ 500MB    │ │ 800MB    │ │ 600MB    │ │ 700MB    │  ← 老 segment     │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘  ← 低频访问文档   │
│                                                                         │
│  本地 SSD Cache（远程 Segment 的缓存）                                    │
│  ┌──────────┐ ┌──────────┐                                             │
│  │ Seg-003  │ │ Seg-004  │  ← 最近被查询过的冷 Segment 缓存             │
│  │ (cached) │ │ (partial)│  ← 支持 block 级别部分缓存                   │
│  └──────────┘ └──────────┘                                             │
└─────────────────────────────────────────────────────────────────────────┘
```

##### 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Segment Tiering Architecture                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Tiering Policy Engine                          │   │
│  │  决定哪些 Segment 应该在哪个存储层                                │   │
│  │  ├── 基于 Segment 年龄                                           │   │
│  │  ├── 基于 Segment 访问频率                                       │   │
│  │  ├── 基于 Segment 内文档的业务标签                                │   │
│  │  └── 基于 Segment 大小（merge 后的大 segment 更适合下沉）         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│          │                                                              │
│          ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Segment Store Abstraction                      │   │
│  │  统一的 Directory 抽象层，屏蔽底层存储差异                         │   │
│  │  ├── LocalDirectory     → 本地 SSD                               │   │
│  │  ├── CachedRemoteDir    → 远程存储 + 本地 LRU Cache              │   │
│  │  └── HybridDirectory    → 路由层，按 Segment 名分发               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│          │                                                              │
│          ▼                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐     │
│  │   Local SSD      │  │  Local SSD Cache  │  │  Object Store    │     │
│  │   (Hot Tier)     │  │  (Warm Cache)     │  │  S3/OSS (Cold)   │     │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘     │
└─────────────────────────────────────────────────────────────────────────┘
```

##### 关键实现模块

**模块一：HybridDirectory（混合目录层）**

Lucene 通过 `Directory` 接口访问底层文件。核心改造点是实现一个 HybridDirectory，让同一个 IndexReader 能同时读取本地和远程的 Segment：

```java
/**
 * 混合 Directory 实现：同一个 Shard 内不同 Segment 文件可以存储在不同介质
 */
public class HybridDirectory extends Directory {

    private final Directory localDirectory;      // 本地 SSD
    private final Directory remoteDirectory;     // 远程对象存储(带缓存)
    private final SegmentTieringPolicy policy;   // 分层策略

    @Override
    public IndexInput openInput(String name, IOContext context) {
        // 根据文件所属 Segment 判断存储位置
        String segmentName = extractSegmentName(name);

        if (policy.isHot(segmentName)) {
            return localDirectory.openInput(name, context);
        } else {
            // 远程 Segment，走缓存层
            return remoteDirectory.openInput(name, context);
        }
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) {
        // 新写入的 Segment 始终写本地
        return localDirectory.createOutput(name, context);
    }

    @Override
    public void deleteFile(String name) {
        // 根据文件位置决定在哪里删除
        String segmentName = extractSegmentName(name);
        if (policy.isHot(segmentName)) {
            localDirectory.deleteFile(name);
        } else {
            remoteDirectory.deleteFile(name);
        }
    }
}
```

**模块二：SegmentTieringPolicy（分层策略引擎）**

决定哪些 Segment 应该留在本地、哪些应该下沉到远程存储：

```java
public class SegmentTieringPolicy {

    private final long ageThresholdMs;         // 超过此年龄的 Segment 可下沉
    private final long accessCountThreshold;   // 低于此访问次数的可下沉
    private final long sizeThresholdBytes;     // merge 后超过此大小的大 segment 适合下沉

    /**
     * 判断 Segment 是否仍然是热数据
     *
     * 策略逻辑：
     * 1. 最近 N 小时内创建的 Segment → Hot（新写入数据大概率是热的）
     * 2. 最近 M 分钟内被查询访问过的 Segment → Hot
     * 3. 包含已被标记为"热文档"的 Segment → Hot（需要额外元数据）
     * 4. 以上都不满足 → Cold，可以下沉
     */
    public boolean isHot(String segmentName) {
        SegmentMetrics metrics = getMetrics(segmentName);

        // 年龄判断
        if (System.currentTimeMillis() - metrics.createTime < ageThresholdMs) {
            return true;
        }

        // 访问频率判断
        if (metrics.recentAccessCount > accessCountThreshold) {
            return true;
        }

        // 业务标签判断（可选）
        if (metrics.hasHotDocuments()) {
            return true;
        }

        return false;
    }

    /**
     * 判断冷 Segment 是否需要回升为热（冷转热）
     * 场景：冷 Segment 被频繁查询时，应该拉回本地
     */
    public boolean shouldPromote(String segmentName) {
        SegmentMetrics metrics = getMetrics(segmentName);
        return metrics.recentAccessCount > promoteThreshold
            && metrics.remoteFetchLatencyP99 > latencyThreshold;
    }
}
```

**模块三：Segment 迁移执行器**

负责将 Segment 文件在本地和远程之间搬迁：

```java
public class SegmentMigrator {

    /**
     * 将 Segment 从本地下沉到远程存储
     * 关键：Segment 是不可变的，所以可以安全拷贝后删除本地副本
     */
    public void demote(String segmentName) {
        // 1. 上传 Segment 所有文件到远程存储
        List<String> files = getSegmentFiles(segmentName);
        for (String file : files) {
            remoteStore.upload(file, localDir.openInput(file));
        }

        // 2. 验证远程文件完整性（checksum 校验）
        for (String file : files) {
            verifyRemoteChecksum(file);
        }

        // 3. 更新 SegmentInfos 中的存储位置元数据
        updateSegmentLocation(segmentName, StorageTier.REMOTE);

        // 4. 删除本地副本（注意：需要确保无进行中的读操作）
        acquireSegmentReadLock(segmentName);
        try {
            for (String file : files) {
                localDir.deleteFile(file);
            }
        } finally {
            releaseSegmentReadLock(segmentName);
        }
    }

    /**
     * 将频繁访问的远程 Segment 提升回本地
     */
    public void promote(String segmentName) {
        List<String> files = getSegmentFiles(segmentName);
        for (String file : files) {
            localDir.copyFrom(remoteStore, file);
        }
        updateSegmentLocation(segmentName, StorageTier.LOCAL);
    }
}
```

**模块四：CachedRemoteDirectory（带缓存的远程目录）**

远程 Segment 的读取必须经过本地缓存，否则查询延迟不可接受：

```java
public class CachedRemoteDirectory extends Directory {

    private final RemoteObjectStore remoteStore;  // S3/OSS
    private final SharedBlobCache blobCache;       // 本地 SSD 缓存

    /**
     * 缓存粒度：不是整个文件，而是 Block（固定大小块，如 16KB）
     * 这样对于大 Segment 文件，只需缓存被查询命中的部分
     */
    @Override
    public IndexInput openInput(String name, IOContext context) {
        return new CachedIndexInput(name, remoteStore, blobCache);
    }
}

public class CachedIndexInput extends IndexInput {

    private static final int BLOCK_SIZE = 16 * 1024; // 16KB per block

    @Override
    public byte readByte() {
        long blockId = getFilePointer() / BLOCK_SIZE;
        byte[] block = blobCache.get(fileName, blockId);

        if (block == null) {
            // Cache miss: 从远程读取整个 block
            block = remoteStore.readRange(fileName, blockId * BLOCK_SIZE, BLOCK_SIZE);
            blobCache.put(fileName, blockId, block);
        }

        return block[(int)(getFilePointer() % BLOCK_SIZE)];
    }

    /**
     * Prefetch: 读取一个 block 时顺便预读后续 N 个 block
     * 因为 Lucene 的访问模式通常是顺序的
     */
    private void prefetch(long currentBlock) {
        for (int i = 1; i <= PREFETCH_BLOCKS; i++) {
            long nextBlock = currentBlock + i;
            if (!blobCache.contains(fileName, nextBlock)) {
                asyncFetch(fileName, nextBlock);
            }
        }
    }
}
```

##### 与 Merge 策略的协同

Segment 分层需要和 Merge Policy 配合。不能让热冷 Segment 混合 merge：

```java
/**
 * 感知分层的 Merge Policy
 * 核心规则：
 * 1. 热 Segment 之间可以 merge（产生新的热 Segment）
 * 2. 冷 Segment 之间可以 merge（在远程完成或拉回本地后 merge）
 * 3. 热+冷 不允许 merge（避免冷数据回升到本地）
 */
public class TieringAwareMergePolicy extends MergePolicy {

    @Override
    public MergeSpecification findMerges(MergeTrigger trigger,
                                          SegmentInfos infos) {
        // 将 Segment 分为 hot 组和 cold 组
        List<SegmentCommitInfo> hotSegments = new ArrayList<>();
        List<SegmentCommitInfo> coldSegments = new ArrayList<>();

        for (SegmentCommitInfo info : infos) {
            if (tieringPolicy.isHot(info.info.name)) {
                hotSegments.add(info);
            } else {
                coldSegments.add(info);
            }
        }

        MergeSpecification spec = new MergeSpecification();

        // 热 Segment 正常 merge（遵循原有 TieredMergePolicy 逻辑）
        spec.add(findMergesInGroup(hotSegments));

        // 冷 Segment 可选 merge（合并小冷 segment 减少文件数）
        // 但冷 merge 优先级低，不抢占 I/O
        if (coldMergeEnabled) {
            spec.add(findMergesInGroup(coldSegments));
        }

        return spec;
    }
}
```

##### Segment 访问统计

判断冷热的关键是跟踪每个 Segment 的访问频率：

```java
/**
 * 在 IndexSearcher 层拦截查询，统计每个 Segment 的访问次数
 */
public class SegmentAccessTracker {

    // 每个 Segment 维护一个滑动窗口计数器
    private final ConcurrentHashMap<String, SlidingWindowCounter> accessCounters;

    /**
     * 在 IndexSearcher.search() 内部，每次 LeafReader 被访问时回调
     */
    public void recordAccess(String segmentName) {
        accessCounters
            .computeIfAbsent(segmentName, k -> new SlidingWindowCounter(windowSize))
            .increment();
    }

    /**
     * 定期扫描：找出需要下沉/提升的 Segment
     */
    public List<String> findSegmentsToDemote() {
        return accessCounters.entrySet().stream()
            .filter(e -> e.getValue().getCount() < coldThreshold)
            .filter(e -> getSegmentAge(e.getKey()) > minAgeForDemotion)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    public List<String> findSegmentsToPromote() {
        return accessCounters.entrySet().stream()
            .filter(e -> e.getValue().getCount() > hotThreshold)
            .filter(e -> isRemoteSegment(e.getKey()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
```

##### 关键难点与解法

```
┌─────────────────────────────────────────────────────────────────────────┐
│  难点                       │  解法                                      │
├─────────────────────────────┼───────────────────────────────────────────┤
│  1. Lucene SegmentInfos     │  扩展 SegmentInfo 的 attributes 字段，    │
│     不记录存储位置           │  增加 tier=local/remote 元数据              │
├─────────────────────────────┼───────────────────────────────────────────┤
│  2. 迁移过程中的并发读      │  COW 策略：迁移前复制到远程，更新路由后     │
│                             │  再删本地；迁移期间两边都有，读任意一份     │
├─────────────────────────────┼───────────────────────────────────────────┤
│  3. NRT (Near Real-Time)    │  新写入的 Segment 始终在本地，refresh 后   │
│     Search 的兼容           │  NRT Reader 仍从本地读，不受远程影响       │
├─────────────────────────────┼───────────────────────────────────────────┤
│  4. Merge 期间的文件引用    │  merge 完成后旧 Segment 才能被回收，需要   │
│                             │  IndexWriter 的 refcount 机制感知远程文件  │
├─────────────────────────────┼───────────────────────────────────────────┤
│  5. Recovery/Replica 同步   │  Peer Recovery 需感知 Segment 位置：       │
│                             │  本地 Segment 走原流程，远程 Segment 只    │
│                             │  传元数据（副本节点直接从远程读）           │
├─────────────────────────────┼───────────────────────────────────────────┤
│  6. 缓存一致性              │  Segment 不可变 → 天然一致，无失效问题     │
│                             │  只需 LRU 淘汰管理缓存空间                 │
├─────────────────────────────┼───────────────────────────────────────────┤
│  7. 性能退化可控性          │  设置冷 Segment 查询超时 + 熔断器          │
│                             │  冷查询超阈值自动触发 promote              │
└─────────────────────────────┴───────────────────────────────────────────┘
```

##### 与现有 Elasticsearch 机制的对比

```
┌──────────────────────────────────────────────────────────────────────┐
│  机制                │  粒度    │  数据迁移  │  对业务透明  │  改造量  │
├──────────────────────┼──────────┼────────────┼──────────────┼─────────┤
│  ILM + Data Tiers   │  索引    │  整索引搬  │  透明        │  零      │
│  Searchable Snapshot │  索引    │  整索引快照│  透明        │  零      │
│  Frozen Tier         │  索引    │  全远程    │  透明        │  零      │
│  本方案(Seg Tier)    │  Segment │  单Seg搬  │  完全透明    │  内核级  │
├──────────────────────┼──────────┼────────────┼──────────────┼─────────┤
│  优势对比：                                                           │
│  - 同一索引内的冷文档自动沉降，无需拆索引                              │
│  - 扩缩容时热 Segment 优先迁移，冷数据无需搬（远程共享）              │
│  - 存储成本降低但查询仍覆盖全量数据                                   │
└──────────────────────────────────────────────────────────────────────┘
```

##### 实施路线

```
Step 1: Directory 抽象层改造
├── 实现 HybridDirectory
├── 实现 CachedRemoteDirectory + SharedBlobCache
├── 单元测试：验证混合读写正确性
└── 预期：2-3 人月

Step 2: Segment 迁移机制
├── 实现 SegmentMigrator (demote/promote)
├── 实现 SegmentAccessTracker
├── 集成 TieringPolicy
└── 预期：2-3 人月

Step 3: Merge Policy 适配
├── 实现 TieringAwareMergePolicy
├── 确保热冷 Segment 不混合 merge
├── 冷 Segment 的远程 merge 支持
└── 预期：1-2 人月

Step 4: Recovery/Replica 适配
├── Peer Recovery 感知远程 Segment
├── 副本节点共享远程 Segment（去重存储）
├── 验证故障恢复场景
└── 预期：2-3 人月

Step 5: 生产化
├── 监控指标（命中率、远程延迟、迁移吞吐）
├── 降级开关（关闭分层回退到全本地）
├── 灰度方案（按索引开启）
└── 预期：1-2 人月

总计：约 8-13 人月
```

**ILM 策略配置示例：**

```json
PUT _ilm/policy/elastic_policy
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": {
            "max_primary_shard_size": "50gb",
            "max_age": "1d"
          },
          "set_priority": { "priority": 100 }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": { "number_of_shards": 1 },
          "forcemerge": { "max_num_segments": 1 },
          "set_priority": { "priority": 50 },
          "allocate": { "number_of_replicas": 0 }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "searchable_snapshot": {
            "snapshot_repository": "my-s3-repo"
          }
        }
      },
      "frozen": {
        "min_age": "90d",
        "actions": {
          "searchable_snapshot": {
            "snapshot_repository": "my-s3-repo",
            "force_merge_index": true
          }
        }
      },
      "delete": {
        "min_age": "365d",
        "actions": { "delete": {} }
      }
    }
  }
}
```

---

### 2.6 Serverless Elasticsearch

#### 2.6.1 Elastic Serverless（官方）

Elastic 于 2023 年推出 Serverless 产品，核心特性：

```
┌─────────────────────────────────────────────────────────────┐
│                 Elastic Serverless Architecture              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  用户视角：                                                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Project（项目）                                       │  │
│  │  - 无需管理节点/分片/副本/ILM                          │  │
│  │  - 按搜索量 + 索引量 + 存储量计费                      │  │
│  │  - 自动伸缩，无容量规划                                │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  内部架构：                                                  │
│  ┌─────────┐  ┌─────────────┐  ┌──────────────────┐       │
│  │ Ingest  │  │   Search    │  │   Object Store   │       │
│  │ Layer   │  │   Layer     │  │   (S3/GCS)       │       │
│  │(无状态) │  │  (无状态)    │  │   (持久存储)      │       │
│  └─────────┘  └─────────────┘  └──────────────────┘       │
│       │              │                    │                  │
│       └──────────────┴────────────────────┘                 │
│                 独立伸缩，互不影响                            │
└─────────────────────────────────────────────────────────────┘
```

**核心变化：**

- 去除 Index/Shard/Replica 概念（用户不可见）
- 写入与查询完全解耦
- 存储层基于对象存储，无限扩展
- 零运维：无需管理集群、升级、打补丁

#### 2.6.2 AWS OpenSearch Serverless

```
┌─────────────────────────────────────────────────────────────┐
│              AWS OpenSearch Serverless                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Collection（集合）                                   │   │
│  │  - 类型：Search / Time-series / Vector Search        │   │
│  │  - 以 OCU (OpenSearch Compute Unit) 计费             │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  内部架构：                                                  │
│  ┌───────────┐     ┌───────────┐     ┌───────────────┐    │
│  │ Indexing  │     │  Search   │     │     S3        │    │
│  │  OCUs     │     │   OCUs    │     │  (Storage)    │    │
│  │ (min: 2)  │     │ (min: 2)  │     │              │    │
│  └───────────┘     └───────────┘     └───────────────┘    │
│       │                  │                   │             │
│  自动扩展            自动扩展           按使用计费           │
│  2→N OCUs           2→N OCUs                              │
└─────────────────────────────────────────────────────────────┘
```

**AWS OpenSearch Serverless 特点：**

| 特性 | 说明 |
|------|------|
| 计费单位 | OCU (OpenSearch Compute Unit) = 6GB RAM + 对应 vCPU |
| 最小规模 | 索引 2 OCU + 搜索 2 OCU (约 $700/月起) |
| 自动扩展 | 基于负载自动增加 OCU |
| 数据冗余 | 自动 2 AZ 冗余，存储在 S3 |
| 限制 | 不支持所有 API、聚合限制、无 ISM 策略 |

---

### 2.7 云原生弹性方案对比

#### 2.7.1 方案全景对比

| 维度 | Elastic Cloud | AWS OpenSearch Serverless | ECK (自建K8s) | 自研方案 |
|------|--------------|--------------------------|---------------|---------|
| **弹性粒度** | 节点级 | OCU 级（更细） | Pod 级 | 可定制 |
| **伸缩速度** | 分钟级 | 秒级 | 分钟级 | 分钟级 |
| **存算分离** | 部分（Frozen Tier） | 完全 | 需自实现 | 可定制 |
| **运维成本** | 低 | 极低 | 中 | 高 |
| **功能完整性** | 完整 | 有限制 | 完整 | 完整 |
| **成本** | 按资源计费 | 按 OCU+存储计费 | 基础设施费用 | 开发+基础设施 |
| **数据主权** | 依赖云厂商 | AWS 锁定 | 完全自主 | 完全自主 |
| **适用场景** | 中大型企业 | 波动大的小型项目 | 已有K8s基础设施 | 有特殊定制需求 |

#### 2.7.2 Elastic Cloud on Kubernetes (ECK)

```yaml
# ECK Autoscaling 配置
apiVersion: autoscaling.k8s.elastic.co/v1alpha1
kind: ElasticsearchAutoscaler
metadata:
  name: es-autoscaler
spec:
  elasticsearchRef:
    name: elastic-cluster
  policies:
  - name: data-hot
    roles: ["data_hot"]
    resources:
      nodeCount:
        min: 3
        max: 10
      cpu:
        min: 4
        max: 16
      memory:
        min: 16Gi
        max: 64Gi
      storage:
        min: 500Gi
        max: 2Ti
  - name: data-warm
    roles: ["data_warm"]
    resources:
      nodeCount:
        min: 1
        max: 5
```

---

## 3. 技术难点

### 3.1 分片迁移的性能影响

```
难点描述：
节点扩缩容触发分片再平衡，大量数据迁移占用网络和磁盘 I/O，
影响正常查询和写入性能。

解决思路：
┌─────────────────────────────────────────────────────────┐
│  1. 限流机制                                            │
│     - 控制并发迁移数                                    │
│     - 限制单节点恢复带宽                                │
│                                                         │
│  2. 调度优化                                            │
│     - 优先迁移小分片                                    │
│     - 低峰期触发大规模迁移                              │
│     - 感知业务负载动态调整迁移速率                       │
│                                                         │
│  3. 架构优化                                            │
│     - 存算分离：新节点无需数据迁移，仅需加载元数据       │
│     - 基于远程存储的 zero-copy 分片分配                  │
│     - Segment 级别的延迟加载                            │
└─────────────────────────────────────────────────────────┘
```

### 3.2 非时序数据的冷热分离

```
难点描述：
ILM 以索引为粒度做分层，但大量业务数据（商品、用户、订单等）
无法按时间维度切分索引。同一索引内文档冷热差异巨大，
无法整体搬迁到低成本存储层。

核心挑战：
┌─────────────────────────────────────────────────────────┐
│  1. 粒度不匹配                                          │
│     - 数据冷热是文档级的                                │
│     - ILM/Data Tier 是索引级的                          │
│     - 无原生文档级分层能力                              │
│                                                         │
│  2. 冷热动态变化                                        │
│     - 商品从冷变热（被推荐/促销）                       │
│     - 用户沉默后再激活                                  │
│     - 需要双向流转，不只是单向降级                       │
│                                                         │
│  3. 分离后的查询一致性                                   │
│     - 搜索时需要同时覆盖冷热数据                        │
│     - 分库后的聚合结果正确性                            │
│     - 排序/分页跨索引合并的复杂度                       │
│                                                         │
│  4. 迁移成本                                            │
│     - Reindex 开销大，影响集群稳定性                    │
│     - 实时性要求高的场景难以 T+1 批量迁移               │
│     - 双写/CDC 方案增加架构复杂度                       │
└─────────────────────────────────────────────────────────┘

推荐方案组合：
- 短期：业务层按规则拆分子索引 + alias 统一查询入口
- 中期：index sort + early termination 减少冷数据扫描
- 长期：探索 Segment 级存储分层（内核改造）
```

### 3.3 分片均衡的决策复杂性

```
难点描述：
多维度约束下的最优分片分配是 NP-Hard 问题：
- 节点磁盘容量约束
- 节点内存约束
- 分片副本不能在同一节点
- 感知 Zone/Rack 容灾约束
- 索引的 hot/warm/cold 属性约束
- 最小化跨节点数据传输

现有算法：
- BalancedShardsAllocator（默认）：基于权重的贪心算法
- DesiredBalanceAllocator（8.6+）：全局最优目标 + 渐进式收敛
```

### 3.3 缩容时的数据安全

```
难点描述：
缩容必须确保数据不丢失，即所有分片在其他节点上有足够副本。

关键保障：
1. 缩容前检查：
   - 确认集群 green 状态
   - 确认目标节点无 unassigned shards
   - 确认其他节点有足够容量接收分片

2. 优雅退出流程：
   - Exclude 节点 → 等待分片全部迁出 → 验证 → 移除节点
   - 设置超时，超时回滚

3. 保护机制：
   - allocation.enable: none（暂停分配）
   - 缩容锁：同一时间仅允许一个缩容操作
```

### 3.4 Searchable Snapshot 查询延迟

```
难点描述：
远程存储的 I/O 延迟比本地 SSD 高 10-100 倍，
冷数据查询性能下降明显。

优化方案：
┌─────────────────────────────────────────────────┐
│  L1 Cache: OS Page Cache (内存)                 │  ← μs 级
│  L2 Cache: Local SSD (shared_cache)            │  ← ms 级
│  L3 Store: Remote Object Store (S3/OSS)        │  ← 10-100ms
└─────────────────────────────────────────────────┘

缓存策略：
- LRU + 频率感知的缓存淘汰
- Prefetch: 预读取相邻 Segment 块
- 并行读取: 同时从多个 S3 分片读取
- 缓存预热: 高频查询对应的 Segment 优先加载
```

### 3.5 集群状态膨胀

```
难点描述：
大量索引/分片导致 Cluster State 过大（数百MB），
Master 节点成为瓶颈，影响集群稳定性。

解决思路：
- Frozen Tier 的 partial mount 减少 cluster state 中的路由信息
- 8.x 引入 Desired Balance Allocator 减少频繁 reroute
- 合理的分片策略（单分片 < 50GB，总分片数 < 节点数 × 1000）
- 定期清理过期索引
```

### 3.6 弹性伸缩的时效性

```
难点描述：
从决策触发到实际生效存在时间差：
- K8s Pod 调度：10-30s
- ES 节点启动：30-60s
- 分片分配：数分钟到数十分钟
- 总延迟：可能 5-30 分钟

优化方向：
1. 预测式扩容：基于历史规律提前扩容
2. 预热池：维护 Warm Standby 节点池
3. 存算分离：无数据迁移，秒级生效
4. Serverless：真正的即时弹性
```

---

## 4. 应用场景

### 4.1 电商大促场景

```
场景特征：
- 流量波峰波谷差异 10-100 倍
- 峰值持续时间短（数小时）
- 对搜索延迟敏感

弹性策略：
┌──────────────────────────────────────────────────────────────┐
│  日常：  5 节点 (Hot) + 2 节点 (Warm)                         │
│                                                              │
│  大促前 1 天：预扩容                                          │
│         15 节点 (Hot) + 2 节点 (Warm)                         │
│                                                              │
│  大促期间：自动伸缩                                           │
│         15-25 节点 (Hot) 基于搜索延迟和 CPU 动态调整           │
│                                                              │
│  大促后 2 小时：逐步缩容                                      │
│         5 节点 (Hot) + 5 节点 (Warm) → 正常状态               │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 日志/可观测性场景

```
场景特征：
- 数据量持续增长，每天 TB 级
- 写多读少，热数据查询频繁
- 历史数据需长期保留但极少访问

弹性策略：
                    写入量
         │ ████
         │ ████████
         │ ████████████
         │ ████████████████
    ─────┼────────────────────── 时间
         │  工作日高峰    周末

推荐架构：
- Hot Tier (SSD): 最近 1-3 天数据，自动扩缩容应对写入波动
- Warm Tier (HDD): 7-30 天数据，固定节点
- Cold/Frozen Tier (S3): 30 天以上，可搜索快照
- 通过 ILM 自动流转，无需人工干预
- 存储成本降低 60-80%
```

### 4.3 SaaS 多租户场景

```
场景特征：
- 不同租户数据量和查询模式差异大
- 需要资源隔离
- 成本需按租户分摊

弹性策略：
┌──────────────────────────────────────────────────┐
│  Tenant A (大客户)                               │
│  ├── 独占 Hot 节点 × 3                           │
│  ├── 独享 Warm 节点 × 2                          │
│  └── 基于 CPU/延迟 独立伸缩                       │
├──────────────────────────────────────────────────┤
│  Tenant B/C/D (小客户)                           │
│  ├── 共享 Hot 节点池                             │
│  ├── 分片级隔离 (index per tenant)               │
│  └── 按聚合负载统一伸缩                           │
└──────────────────────────────────────────────────┘
```

### 4.4 向量搜索/AI 场景

```
场景特征：
- 向量索引内存占用大
- 查询负载波动大（模型推理触发批量查询）
- 需要 GPU/高内存节点

弹性策略：
- 基于 ML 节点的独立伸缩策略
- 向量索引的分段加载（quantized vectors 降低内存）
- 查询高峰自动扩展搜索节点
- 利用 Frozen Tier 存储历史向量数据
```

---

## 5. 实施建议

### 5.1 阶段性实施路线

```
Phase 1 (1-2 月)：分层存储
├── 实施 ILM 策略
├── 配置 Hot/Warm/Cold 分层
├── 验证 Searchable Snapshots
└── 预期收益：存储成本降低 50%+

Phase 2 (2-3 月)：自动伸缩
├── 接入 K8s + ECK 或自研控制器
├── 实现基于指标的自动扩缩容
├── 制定扩缩容策略和安全边界
└── 预期收益：资源利用率提升至 60%+

Phase 3 (3-6 月)：存算分离
├── 评估存算分离架构可行性
├── 核心路径改造（写入/查询分离）
├── 本地缓存 + 远程存储集成
└── 预期收益：弹性速度从分钟级到秒级

Phase 4 (6-12 月)：Serverless化
├── 多租户资源池化
├── 按需计费模型
├── 完全自动化运维
└── 预期收益：接近零运维，按使用量计费
```

### 5.2 关键指标体系

| 指标类别 | 具体指标 | 告警阈值 |
|---------|---------|---------|
| 伸缩效率 | 扩容生效时间 | > 10min 告警 |
| 资源利用率 | CPU/Memory/Disk 使用率 | < 20% 触发缩容，> 70% 触发扩容 |
| 查询性能 | P99 搜索延迟 | > 500ms 告警 |
| 数据安全 | 集群状态 (Green/Yellow/Red) | 非 Green 阻止缩容 |
| 成本效率 | 每 GB 存储成本 / 每千次查询成本 | 月环比上升 > 20% |

---

## 6. 总结

| 方案 | 弹性能力 | 适用阶段 | 复杂度 |
|------|---------|---------|--------|
| ILM 分层存储 | 存储弹性 | 立即可用 | 低 |
| ECK + K8s | 计算弹性（分钟级） | 有 K8s 基础设施 | 中 |
| Searchable Snapshots | 存算部分分离 | ES 7.12+ | 中 |
| 自研 Autoscaler | 定制化弹性 | 有研发能力 | 高 |
| Serverless (Elastic/AWS) | 完全弹性 | 云上部署 | 低（用户侧） |
| 自研存算分离 | 极致弹性 | 深度定制 | 极高 |

**建议优先级：** ILM 分层 → Searchable Snapshots → K8s 弹性 → 存算分离探索

---

## 7. 源码分析与设计优化点

基于 Elasticsearch 8.19.8 和 OpenSearch 源码阅读，以下是对设计方案的补充优化建议。

### 7.1 ES SharedBlobCacheService 的设计启示

**源码位置：** `x-pack/plugin/blob-cache/src/main/java/org/elasticsearch/blobcache/shared/SharedBlobCacheService.java`（2018行）

ES 的 Frozen Tier 已经实现了我们方案中 Segment 级远程读取的核心能力，但它是 **索引粒度** 的（整个索引挂载为 searchable snapshot），关键设计细节：

```
┌─────────────────────────────────────────────────────────────────────────┐
│  ES SharedBlobCacheService 实际设计                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Region 化设计（非简单 Block）                                         │
│     - 默认 Region Size = Range Size = 16MB                              │
│     - Recovery Range Size = 128KB（恢复阶段小粒度读取）                    │
│     - Cache 以 Region 为最小分配单位                                     │
│     - 内部用 SharedBytes（mmap 或 file channel）管理物理缓存空间          │
│                                                                         │
│  2. LFU 淘汰策略 + 频率衰减                                              │
│     - 不是简单 LRU，而是 LFU（Least Frequently Used）                    │
│     - 带 decay interval（默认 60s）周期性衰减频率计数                      │
│     - max_freq 限制（默认 100），防止热数据永远不被淘汰                    │
│     - min_time_delta（默认 60s）控制频率计数的最小间隔                     │
│                                                                         │
│  3. SparseFileTracker                                                   │
│     - 追踪文件中已缓存的字节范围（非连续的稀疏区域）                       │
│     - 支持并发的 range-level 填充和等待                                   │
│     - 精确到字节的缓存粒度跟踪                                           │
│                                                                         │
│  4. 节点角色限制                                                         │
│     - 仅 data_frozen / search / indexing 角色节点可启用                   │
│     - 默认使用磁盘 90%（max_headroom 100GB）                             │
└─────────────────────────────────────────────────────────────────────────┘
```

**对我们 Segment 级方案的优化：**

| 原方案设计 | 源码启示的优化 |
|-----------|--------------|
| Block 级缓存 (16KB) | 应采用 Region 级 (16MB) + SparseFileTracker 追踪稀疏区域。16KB 太小会导致元数据开销爆炸 |
| 简单 LRU 淘汰 | 应使用 LFU + decay，更好地区分"偶尔一次"和"持续热门" |
| 文件级缓存状态 | 应用 SparseFileTracker 做字节级的已缓存/未缓存追踪 |
| 同步远程读取 | ES 使用 populateAndRead 模式：读缓存+异步填充同时进行 |

### 7.2 ES FrozenIndexInput 的 fast-path 设计

**源码位置：** `x-pack/plugin/searchable-snapshots/src/main/java/org/elasticsearch/xpack/searchablesnapshots/store/input/FrozenIndexInput.java`

```java
// FrozenIndexInput.readWithoutBlobCache() 的关键模式：
// 1. fast-path: tryRead 检查缓存是否命中，命中直接返回
// 2. slow-path: populateAndRead 同时读取 + 异步填充缓存

// 这对我们的启示：
// - CachedRemoteDirectory 不应是简单的 "命中返回/miss远程读"
// - 而应是 "命中返回 / miss则从远程拉取一个 range 并缓存，同时返回所需数据"
// - range 大小区分恢复模式 (128KB) vs 正常模式 (16MB)
```

**优化建议：CachedRemoteDirectory 应支持两种读取模式：**

```
正常查询模式：range_size = 16MB
  → 缓存预热效果好，减少后续 miss 次数
  → 适合顺序扫描场景（倒排列表遍历）

恢复/首次加载模式：range_size = 128KB
  → 快速响应首次查询
  → 减少不必要的远程读取量
  → 按需逐步加载
```

### 7.3 OpenSearch Remote Store 的存算分离启示

**源码位置：** `server/src/main/java/org/opensearch/index/store/RemoteSegmentStoreDirectory.java`

OpenSearch 的 Remote Store 方案与我们的 Segment 级分层有本质区别：

```
┌─────────────────────────────────────────────────────────────────────────┐
│  OpenSearch Remote Store vs 我们的 Segment Tiering                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  OpenSearch 方案：                                                       │
│  ├── 所有 Segment 都上传到远程存储（Remote Store Backed Index）           │
│  ├── 本地仅作为写入缓冲 + 热缓存                                        │
│  ├── Segment Replication：副本节点从远程存储拉 Segment（而非文档重放）     │
│  ├── 每个 Segment 上传时带 UUID 后缀防覆盖（_0.cfe__gX7bNIIBrs0AUNsR2yEG）│
│  └── 远程存储作为 Source of Truth                                        │
│                                                                         │
│  对比：                                                                  │
│  ├── OpenSearch: 全量远程 + 本地缓存（write-through）                    │
│  ├── ES Frozen: 全量远程 + 按需加载（read-through）                      │
│  └── 我们的方案: 选择性远程 + 策略驱动迁移（tiering）                     │
│                                                                         │
│  关键差异：                                                              │
│  ├── OpenSearch 无"选择"问题——所有 Segment 都走远程                       │
│  ├── ES Frozen 也无"选择"问题——整个索引都是远程                           │
│  └── 我们的方案需要决策引擎判断哪些 Segment 冷、何时迁移                  │
└─────────────────────────────────────────────────────────────────────────┘
```

**OpenSearch 的 FileCache 设计借鉴：**

```
FileCache (opensearch) 核心特性：
├── 节点级视图：一个节点共享一个 FileCache 实例
├── LRU 优先级管理：put 到队首，get 提升优先级
├── 容量满时从队尾淘汰 + 回调清理磁盘文件
├── SegmentedCache：分段减少锁竞争
└── RefCounted：引用计数防止正在使用的缓存被淘汰

对我们方案的优化：
├── 缓存应是节点级单例，跨索引共享（ES 已这样做）
├── 必须有引用计数 → 正在查询的 Segment 不能被淘汰
└── 分段缓存减少高并发下的锁竞争
```

### 7.4 OpenSearch Tiering Service 的状态机设计

**源码位置：** `server/src/main/java/org/opensearch/storage/tiering/TieringService.java`

OpenSearch 已经实现了索引级的 Hot→Warm→Hot 双向分层（`HotToWarmTieringService` / `WarmToHotTieringService`），其状态机设计值得借鉴：

```
OpenSearch TieringState 状态机：
┌─────┐  tiering request  ┌──────────────┐  shard relocation done  ┌──────┐
│ HOT │ ─────────────────▶ │ HOT_TO_WARM  │ ──────────────────────▶ │ WARM │
└─────┘                    └──────────────┘                         └──────┘
   ▲                                                                    │
   │         cancel / warm-to-hot request                               │
   │   ┌──────────────┐                                                │
   └── │ WARM_TO_HOT  │ ◀──────────────────────────────────────────────┘
       └──────────────┘

关键设计：
1. TieringState 作为 IndexMetadata 的属性，集群状态中持久化
2. 状态变化通过 ClusterStateUpdateTask 原子更新
3. 支持并发限流 (H2W_MAX_CONCURRENT_TIERING_REQUESTS)
4. DiskThresholdEvaluator 评估目标节点容量
5. FileCacheSettings 管控 warm 节点的缓存策略
6. 双向流转：warm → hot 支持回升（我们的设计也需要这个）
```

**对 Segment 级方案的启示：**

我们的 Segment 级分层如果采用类似状态机，应该是：

```
Segment Level TieringState:
┌───────┐  cold decision  ┌──────────────────┐  upload done    ┌────────┐
│ LOCAL │ ──────────────▶  │ LOCAL_TO_REMOTE  │ ─────────────▶  │ REMOTE │
└───────┘                  └──────────────────┘                 └────────┘
    ▲                                                               │
    │            access frequency spike                              │
    │   ┌──────────────────┐                                       │
    └── │ REMOTE_TO_LOCAL  │ ◀─────────────────────────────────────┘
        └──────────────────┘

与 OpenSearch 的区别：
- 粒度从索引级降到 Segment 级
- 状态变化频率更高，需要更轻量的元数据管理
- 不需要 ClusterState 更新（太重），可用 shard 级本地元数据
```

### 7.5 ES DesiredBalance Allocator 对弹性扩缩容的优化

**源码位置：** `server/src/main/java/org/elasticsearch/cluster/routing/allocation/allocator/DesiredBalanceComputer.java`

ES 8.6+ 引入的 DesiredBalance 分配器是弹性伸缩的关键改进：

```
┌─────────────────────────────────────────────────────────────────────────┐
│  DesiredBalance 三阶段设计                                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. DesiredBalanceComputer（计算期望状态）                                │
│     - 输入：当前集群状态 + 前一次期望状态                                 │
│     - 模拟所有进行中的 recovery 都已完成                                  │
│     - 委托给 BalancedShardsAllocator 计算理想分配                        │
│     - 输出：每个 ShardId → 期望的 nodeId 集合                            │
│     - 支持时间上限（index creation 期间 max 1s）                          │
│     - 支持 isFresh 检查：计算过程中发现输入过期则中止                     │
│                                                                         │
│  2. DesiredBalanceReconciler（渐进式调和）                                │
│     - 步骤 1: allocateUnassigned → 分配未分配的分片                      │
│     - 步骤 2: moveShards → 移动不能留在当前位置的分片                    │
│     - 步骤 3: balance → 将分片移向期望位置                               │
│     - 使用 NodeAllocationOrdering 保证轮次公平                           │
│     - 监控 undesiredAllocations ratio 指标                               │
│                                                                         │
│  3. ContinuousComputation（持续计算循环）                                 │
│     - 后台线程不断接收新 input 重算 desired balance                      │
│     - 变化时触发 reroute，否则静默                                       │
│                                                                         │
│  核心优势：                                                              │
│  - 计算和执行解耦：期望状态计算不阻塞集群操作                             │
│  - 渐进收敛：不追求一步到位，逐步逼近期望状态                             │
│  - 持续适应：集群变化时自动重算期望状态                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

**对自研 Autoscaler 的优化建议：**

| 当前方案 | 优化点 |
|---------|-------|
| 直接触发扩缩 + 等待分片迁移 | 应分离"计算期望状态"和"执行迁移"，异步渐进 |
| 扩容后立即 rebalance | 应让 DesiredBalance 自行收敛，避免频繁全量 reroute |
| 无状态对比（当前vs阈值） | 应维护"期望集群规模"状态，减少抖动 |
| 单次决策 | 应采用持续计算模式，新节点加入时自动重算分配 |

### 7.6 ES Autoscaling Decider 的局限性与增强方向

**源码位置：** `x-pack/plugin/autoscaling/src/main/java/org/elasticsearch/xpack/autoscaling/storage/`

阅读 ReactiveStorageDeciderService（1141行）和 ProactiveStorageDeciderService 后发现：

```
当前 ES Autoscaling 的局限性：
┌─────────────────────────────────────────────────────────────────────────┐
│  1. 仅支持存储维度的决策                                                 │
│     - ReactiveStorage: 当前分片无法分配时触发（已经来不及了）             │
│     - ProactiveStorage: forecast_window 仅 30 分钟（太短）               │
│     - 无 CPU/Memory/Latency 维度的 Decider                              │
│                                                                         │
│  2. 预测能力不足                                                         │
│     - Proactive 仅线性外推索引增长率                                     │
│     - 不感知业务周期（大促、工作日/周末）                                 │
│     - 不支持自定义预测模型                                               │
│                                                                         │
│  3. 计算与执行的gap                                                      │
│     - Autoscaling API 仅输出 required_capacity                           │
│     - 执行完全依赖外部编排器（ECE/ECK/Elastic Cloud）                    │
│     - 开源用户无执行器可用                                               │
│                                                                         │
│  4. 无搜索负载感知                                                       │
│     - 不关注 search latency / throughput                                 │
│     - 搜索负载突增时不会触发扩容                                         │
│     - 仅关注"能否存得下"，不关注"能否查得动"                              │
└─────────────────────────────────────────────────────────────────────────┘
```

**自研方案应补充的 Decider：**

```java
// 建议新增的 Decider 维度
ComputeDecider:
  - 基于 CPU 使用率 (thread_pool stats)
  - 基于 GC pressure (heap usage + gc overhead)
  - 触发阈值: cpu_avg > 70% && duration > 5min

LatencyDecider:
  - 基于搜索 P99 延迟
  - 基于 indexing latency
  - 触发阈值: search_p99 > SLA * 1.5 && duration > 3min

QueueDecider:
  - 基于 search/bulk 线程池队列积压和拒绝率
  - 触发阈值: rejected_count > 0 || queue_size > capacity * 0.8

PredictiveDecider:
  - 基于时间序列预测（不是简单线性外推）
  - 支持周期性模式识别（日级/周级）
  - 提前 N 小时预扩容（N 可配置）
```

### 7.7 OpenSearch Segment Replication 对副本扩展的启示

**源码位置：** `server/src/main/java/org/opensearch/indices/replication/SegmentReplicationTarget.java`

OpenSearch 的 Segment Replication 是对 ES 传统 Document Replication 的替代：

```
传统 Document Replication（ES 默认）：
Primary → 写入文档 → 转发到 Replica → Replica 独立构建 Lucene Segment
问题：每个副本独立 index，CPU 消耗 = 主分片 × 副本数

Segment Replication（OpenSearch）：
Primary → 写入文档 → 构建 Segment → 将 Segment 文件复制给 Replica
优势：Replica 不需要重新 index，仅需下载 Segment 文件

对弹性架构的价值：
┌─────────────────────────────────────────────────────────────────────────┐
│  1. 扩容副本更快                                                         │
│     - 新副本节点仅需拉取 Segment 文件，无需重放操作日志                    │
│     - 结合 Remote Store，副本从远程存储拉取（无需主分片参与）              │
│                                                                         │
│  2. 降低主节点写入压力                                                    │
│     - 主节点只负责构建 Segment，不需要处理副本的 indexing 请求             │
│     - 扩副本不增加主节点 CPU 负载                                         │
│                                                                         │
│  3. 存算分离的天然配合                                                    │
│     - 主节点 flush Segment → 上传远程存储                                │
│     - 副本节点从远程存储下载 Segment → 加载                               │
│     - 副本节点变成"纯查询节点"，可以快速弹性伸缩                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**对自研方案的优化建议：**

如果实现存算分离 + 弹性伸缩，Segment Replication 模式让"查询节点扩容"变得极快：
- 新节点启动 → 加载索引元数据 → 从远程存储按需拉取 Segment → 立即可查
- 无需等待 peer recovery（传统模式可能要数十分钟）
- 搜索高峰时可秒级扩出只读查询节点

### 7.8 综合优化建议总结

```
┌─────────────────────────────────────────────────────────────────────────┐
│  基于源码阅读的 Top 优化项                                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Priority 1（直接可借鉴，改动小）：                                       │
│  ├── [缓存] 采用 Region(16MB) + SparseFileTracker 替代简单 Block 缓存    │
│  ├── [缓存] LFU + decay 策略替代 LRU                                    │
│  ├── [读取] populateAndRead 模式：读数据 + 异步填充缓存并行               │
│  └── [缩容] 利用 DesiredBalance 渐进收敛，避免缩容时的分片风暴           │
│                                                                         │
│  Priority 2（架构优化，改动中）：                                         │
│  ├── [扩缩] 新增 Compute/Latency/Queue Decider 补充搜索负载感知         │
│  ├── [分层] Segment 级状态机（LOCAL → REMOTE 双向）参考 OpenSearch Tiering│
│  ├── [副本] 引入 Segment Replication 让查询节点扩容达到秒级               │
│  └── [预测] 替换线性外推为周期性时序预测                                  │
│                                                                         │
│  Priority 3（深度改造，改动大）：                                         │
│  ├── [存储] 全量 Remote Store（OpenSearch 模式）作为终态架构               │
│  ├── [计算] 查询节点完全无状态化（Segment 全从远程加载）                  │
│  └── [调度] 计算与执行解耦的持续调和模式（参考 DesiredBalance 设计）       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 8. OpenSearch Tiering Service 详解

基于 OpenSearch 源码的深入分析，完整解读其索引级 Hot↔Warm 双向分层服务实现。

### 8.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OpenSearch Tiering Service Architecture                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                   TieringService (抽象基类)                          │   │
│  │  - ClusterStateListener：监听集群状态变化                            │   │
│  │  - 管理 tieringIndices（正在迁移中的索引集合）                        │   │
│  │  - 提供 tier() / cancelTiering() / getTieringStatus() 接口          │   │
│  └───────────────────────┬──────────────────────┬──────────────────────┘   │
│                          │                      │                           │
│            ┌─────────────┴─────────┐  ┌────────┴──────────────┐            │
│            │ HotToWarmTieringService│  │ WarmToHotTieringService│            │
│            │ (Hot → Warm)          │  │ (Warm → Hot)           │            │
│            └───────────────────────┘  └───────────────────────┘            │
│                                                                             │
│  依赖组件：                                                                  │
│  ├── ClusterService            → 集群状态更新                               │
│  ├── AllocationService         → 触发 reroute 实现分片迁移                  │
│  ├── DiskThresholdEvaluator    → 评估目标节点磁盘容量                       │
│  ├── FileCacheSettings         → Warm 节点的文件缓存配置                    │
│  ├── ShardLimitValidator       → 验证分片数限制                             │
│  └── ClusterInfoService        → 集群磁盘/负载信息                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 状态机与生命周期

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Index TieringState 状态机                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                    tier() API                                                │
│  ┌─────┐  ─────────────────────────▶  ┌──────────────┐                     │
│  │ HOT │                               │ HOT_TO_WARM  │                     │
│  └─────┘  ◀─────────────────────────  └──────────────┘                     │
│     ▲        cancelTiering() API             │                              │
│     │                                        │ 所有分片到达 Warm 节点        │
│     │                                        ▼                              │
│     │                                  ┌──────────────┐                     │
│     │        cancelTiering() API       │    WARM      │                     │
│     │     ┌──────────────┐             └──────────────┘                     │
│     └──── │ WARM_TO_HOT  │ ◀────────────────┘                              │
│           └──────────────┘   tier() API                                     │
│                  │                                                           │
│                  │ 所有分片到达 Hot 节点                                      │
│                  └──────────────▶ HOT                                        │
│                                                                             │
│  状态存储位置：IndexMetadata.settings["index.tiering_state"]                 │
│  持久化：通过 ClusterState 持久化，集群重启/主节点切换后可恢复               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.3 核心流程：tier() 触发分层

```java
// 源码位置: storage/tiering/TieringService.java:470
// 以 Hot → Warm 为例的完整流程：

tier(IndexTieringRequest request, ActionListener listener, ClusterState state) {
    // Step 1: 解析索引
    Index index = resolveRequestIndex(request.getIndex(), state);

    // Step 2: 提交 ClusterStateUpdateTask (URGENT 优先级)
    clusterService.submitStateUpdateTask("hot_to_warm", new ClusterStateUpdateTask(URGENT) {

        execute(ClusterState currentState) {
            // 2.1 防重入检查
            if (tieringIndices.contains(index)) return currentState;

            // 2.2 前置验证（见 8.4 验证链）
            validateTieringRequest(currentState, clusterInfoService, tieringIndices, ...);

            // 2.3 更新 IndexMetadata
            Settings newSettings = Settings.builder()
                .put(indexMetadata.getSettings())
                .put("index.warm", true)                    // 标记为 warm 索引
                .put("index.tiering_state", "HOT_TO_WARM") // 状态变更
                .put("index.composite_store_type", "tiered_composite") // 存储类型
                .build();

            // 2.4 强制副本数为 1（warm 节点不需要多副本）
            if (currentReplicas != 1) {
                routingTableBuilder.updateNumberOfReplicas(1, indices);
            }

            // 2.5 记录迁移开始时间
            tieringCustomData.put("h2w_tiering_start_time", System.currentTimeMillis());

            // 2.6 触发 reroute → 分片分配器会将分片迁移到 warm 节点
            return allocationService.reroute(updatedState, "hot_to_warm");
        }

        clusterStateProcessed(...) {
            // 2.7 加入追踪集合
            tieringIndices.add(index);
            listener.onResponse(success);
        }
    });
}
```

### 8.4 验证链（Validation Chain）

OpenSearch 的分层验证是多层级的，体现了生产环境的严谨性：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TieringServiceValidator 验证链                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  validateCommon (通用验证):                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  1. validateWarmNodes()                                              │   │
│  │     → 集群中是否存在 warm 角色节点                                    │   │
│  │                                                                      │   │
│  │  2. validateRemoteStoreEnabled()                                     │   │
│  │     → 索引必须启用 Remote Store（必要前提）                            │   │
│  │                                                                      │   │
│  │  3. validateShardLimit()                                             │   │
│  │     → 迁移后分片数不超过集群限制                                      │   │
│  │                                                                      │   │
│  │  4. validateCCRIndex()                                               │   │
│  │     → 跨集群复制索引不允许迁移到 Warm                                 │   │
│  │                                                                      │   │
│  │  5. validateIndexCurrentState()                                      │   │
│  │     → 状态机合法性：HOT 才能 → WARM，WARM 才能 → HOT                 │   │
│  │                                                                      │   │
│  │  6. validateIndexHealth()                                            │   │
│  │     → RED 状态索引禁止迁移                                            │   │
│  │                                                                      │   │
│  │  7. enforceBackpressure()                                            │   │
│  │     → 并发迁移数 < maxConcurrentTieringRequests                      │   │
│  │                                                                      │   │
│  │  8. checkJVMMemoryUtilizationThreshold()                             │   │
│  │     → 目标节点 JVM 使用率 < 阈值                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  validateHotToWarmTiering (Hot→Warm 专项):                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  9. validateWarmNodeDiskThresholdWaterMarkLow()                       │   │
│  │     → Warm 节点磁盘使用率不超过 low watermark                         │   │
│  │                                                                      │   │
│  │  10. checkFileCacheActiveUsage()  [TODO - 暂未启用]                   │   │
│  │      → Warm 节点文件缓存活跃使用率 < 阈值                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  validateWarmToHotTiering (Warm→Hot 专项):                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  9. validateEligibleHotNodesCapacity()                               │   │
│  │     → Hot 节点有足够磁盘空间接收数据                                   │   │
│  │                                                                      │   │
│  │  10. validateSpaceForLargestShard()                                  │   │
│  │      → 至少一个 Hot 节点能容纳最大分片 + 20GB buffer                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  拒绝原因枚举 (RejectionReason):                                            │
│  ├── INVALID_TIER_TRANSITION  → 非法状态转换                                │
│  ├── INDEX_RED_STATUS         → 索引红色状态                                │
│  ├── REMOTE_STORE_NOT_ENABLED → 未启用远程存储                              │
│  ├── SHARD_LIMIT_EXCEEDED     → 分片数超限                                  │
│  ├── CCR_INDEX_REJECTION      → CCR 索引不允许                              │
│  └── BACKPRESSURE             → 并发限流                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.5 分片迁移监控：clusterChanged 事件驱动

```java
// 源码位置: storage/tiering/TieringService.java:220
// TieringService 实现 ClusterStateListener，仅在 Master 节点执行

clusterChanged(ClusterChangedEvent event) {
    if (event.localNodeClusterManager()) {

        // 场景 1: 新当选 Master → 重建内存中的迁移追踪状态
        if (previousState 不是 master || 集群刚恢复) {
            reconstructInProgressTieringRequests(state);
            // 扫描所有索引的 INDEX_TIERING_STATE，找到 HOT_TO_WARM/WARM_TO_HOT 的索引
            // 重建 tieringIndices 集合
        }

        // 场景 2: 路由表变化 → 检查正在迁移的索引是否完成
        if (event.routingTableChanged() && tieringIndices 不为空) {
            processTieringInProgress(state);
        }
    }
}

// processTieringInProgress 的核心逻辑：
processTieringInProgress(ClusterState clusterState) {
    for (Index index : tieringIndices) {
        List<ShardRouting> shards = clusterState.routingTable().allShards(index);

        boolean allComplete = true;
        for (ShardRouting shard : shards) {
            if (!isShardInTargetTier(shard, clusterState)) {
                allComplete = false;
                break;
            }
        }

        if (allComplete) {
            completedInBatch.add(index);
        }
    }

    // 批量更新完成的索引
    if (!completedInBatch.isEmpty()) {
        updateClusterStateForTieredIndices(completedInBatch);
        // → 将 INDEX_TIERING_STATE 从 HOT_TO_WARM 更新为 WARM
        // → 移除 TIERING_CUSTOM_KEY 元数据
        // → 从 tieringIndices 中移除
    }
}
```

### 8.6 取消流程与安全保障

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    cancelTiering() 流程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 验证取消请求合法性：                                                     │
│     ├── 索引确实在迁移中（tieringIndices 或 INDEX_TIERING_STATE）            │
│     ├── 索引尚未到达目标状态（已完成的不能取消）                             │
│     └── 索引未被删除                                                         │
│                                                                             │
│  2. 回滚 IndexMetadata：                                                    │
│     ├── index.warm = false                                                  │
│     ├── index.tiering_state = HOT                                           │
│     ├── index.composite_store_type = default                                │
│     └── 移除 TIERING_CUSTOM_KEY                                             │
│                                                                             │
│  3. 触发 reroute：                                                          │
│     → AllocationService.reroute() 让分配器将分片迁回原来的 tier              │
│                                                                             │
│  4. DFA (Data Format Aware) 索引的特殊处理：                                 │
│     ├── 取消时 NOT 立即移除 write block                                      │
│     ├── 等待所有分片确认回到 hot 节点（writable engine）                     │
│     └── removeWriteBlockForCancelledDfaIndices() 在后续 clusterChanged 中执行│
│                                                                             │
│  安全性设计：                                                                │
│  ├── cancelTiering 使用 IMMEDIATE 优先级（高于 tier 的 URGENT）              │
│  ├── Master 故障转移后能从 ClusterState 重建状态                             │
│  └── DFA 索引写保护：防止分片还在 warm 时接受写入                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.7 Hot → Warm 的具体变化

当索引从 Hot 迁移到 Warm，OpenSearch 做了以下变更：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  IndexMetadata 设置变更                                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  index.warm = true                                                          │
│  index.tiering_state = HOT_TO_WARM → WARM                                  │
│  index.composite_store_type = tiered_composite                              │
│  index.number_of_replicas = 1（强制降为 1 副本）                              │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  存储层变化                                                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Hot 节点上的分片：                                                          │
│  ├── 数据存储在本地 SSD                                                      │
│  ├── 完整的 Lucene Segment 文件                                              │
│  └── 全功能读写                                                              │
│                                                                             │
│  Warm 节点上的分片：                                                         │
│  ├── Segment 数据在 Remote Store (S3/等)                                     │
│  ├── 本地通过 FileCache 缓存热点 Segment 文件                                │
│  ├── 按需从远程加载（OnDemandBlockSnapshotIndexInput）                       │
│  └── 只读（DFA 索引会加 write block）                                        │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  Warm 节点的数据读取路径                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  查询请求 → Warm 节点                                                       │
│     │                                                                        │
│     ├── FileCache 命中？                                                     │
│     │   ├── 是 → 直接从本地缓存读取                                         │
│     │   └── 否 → TransferManager 从 Remote Store 拉取                       │
│     │            └── 以 Block 为单位（AbstractBlockIndexInput）              │
│     │            └── 写入本地 FileCache                                      │
│     │            └── 返回给查询引擎                                          │
│     │                                                                        │
│     └── FileCache 满？                                                       │
│         └── LRU 淘汰低优先级文件 → 回调删除磁盘文件                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.8 并发控制与背压机制

```java
// 源码位置: storage/common/tiering/TieringServiceValidator.java

// 1. 并发迁移数限制
enforceBackpressure(index, tieringIndicesSize, maxConcurrentTieringRequests) {
    if (tieringIndicesSize >= maxConcurrentTieringRequests) {
        throw new TieringRejectionException(BACKPRESSURE, ...);
        // 防止同时迁移过多索引导致集群不稳定
    }
}

// 2. JVM 内存压力检查
checkJVMMemoryUtilizationThreshold(clusterState, clusterInfo, threshold) {
    // 检查目标 tier 节点的 JVM 使用率
    // 超过阈值时拒绝新的迁移请求
    // 保护节点不因迁移导致 OOM
}

// 3. 磁盘水位线检查（Hot→Warm 方向）
validateWarmNodeDiskThresholdWaterMarkLow() {
    // 检查 warm 节点磁盘使用率是否超过 low watermark
    // 超过则拒绝，避免迁移导致 warm 节点磁盘满
}

// 4. 最大分片容量检查（Warm→Hot 方向）
validateSpaceForLargestShard(clusterState, clusterInfo, index, HOT, 20GB) {
    // 确保至少一个 hot 节点能容纳索引中最大的分片
    // 额外预留 20GB 缓冲空间
}
```

### 8.9 Master 故障转移的容错设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Master Failover 场景下的状态恢复                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  问题：                                                                      │
│  - tieringIndices 是内存中的 ConcurrentHashSet                              │
│  - Master 切换后新 Master 的 tieringIndices 为空                             │
│  - 但 ClusterState 中 INDEX_TIERING_STATE = HOT_TO_WARM 已经持久化          │
│                                                                             │
│  解法 (reconstructInProgressTieringRequests):                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  新 Master 当选时：                                                  │   │
│  │  for (IndexMetadata in clusterState.metadata().indices()) {          │   │
│  │      if (settings["index.tiering_state"] == "HOT_TO_WARM"            │   │
│  │       || settings["index.tiering_state"] == "WARM_TO_HOT") {         │   │
│  │          tieringIndices.add(index);  // 重建追踪集合                  │   │
│  │      }                                                               │   │
│  │  }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  取消请求的容错：                                                            │
│  - 取消验证同时检查 tieringIndices (内存) 和 INDEX_TIERING_STATE (持久化)    │
│  - 任一满足即允许取消，避免"内存未重建但状态确实在迁移中"的窗口              │
│                                                                             │
│  设计启示：                                                                  │
│  - 核心状态必须持久化到 ClusterState                                         │
│  - 内存追踪仅用于快速路径，ClusterState 才是 Source of Truth                 │
│  - 任何节点/Master 故障不会导致迁移"卡死"或"丢失"                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.10 与 Elasticsearch 方案的对比分析

| 维度 | OpenSearch TieringService | ES ILM + Data Tiers |
|------|--------------------------|---------------------|
| **操作粒度** | 索引级 | 索引级 |
| **触发方式** | 手动 API 调用 | 基于时间/大小自动触发 |
| **方向** | 双向（Hot↔Warm） | 单向（Hot→Warm→Cold→Frozen） |
| **状态持久化** | ClusterState (IndexMetadata) | ClusterState (ILM Policy) |
| **进度追踪** | clusterChanged 事件驱动检查 | ILM Step 状态机 |
| **并发控制** | maxConcurrentTieringRequests | 全局 concurrent recoveries |
| **容量评估** | 磁盘水位+JVM+FileCache | 磁盘水位 |
| **存储模型** | Remote Store + FileCache | Searchable Snapshot + SharedBlobCache |
| **副本策略** | 强制降为 1 副本 | 可配置（allocate action） |
| **取消能力** | 支持，回滚到原状态 | 不支持中途取消（只能 retry） |
| **故障恢复** | 从 ClusterState 重建 | ILM step 自带重试 |

### 8.11 对我们 Segment 级分层方案的设计指导

从 OpenSearch TieringService 中提取的关键设计原则：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  设计原则提取                                                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 状态必须持久化                                                           │
│     OpenSearch: INDEX_TIERING_STATE 写入 IndexMetadata (ClusterState)        │
│     我们的方案: Segment 级状态不能放 ClusterState（太频繁），应放 shard 级    │
│     本地元数据文件 + 定期 checkpoint 到远程                                   │
│                                                                             │
│  2. 事件驱动而非轮询                                                         │
│     OpenSearch: clusterChanged → 检查路由表变化 → 判断迁移是否完成           │
│     我们的方案: 应在 IndexSearcher 层 hook 查询事件 → 统计访问频率           │
│     定期触发冷热判定，而非轮询 Segment 状态                                  │
│                                                                             │
│  3. 双向流转是必需的                                                         │
│     OpenSearch: Hot→Warm 和 Warm→Hot 都有完整实现                            │
│     我们的方案: Segment 下沉(demote) 和 回升(promote) 必须对称设计            │
│     冷 Segment 被频繁查询时需要自动回升                                      │
│                                                                             │
│  4. 验证前置，不要让非法操作进入执行阶段                                     │
│     OpenSearch: preflightValidate → validate in ClusterStateTask             │
│     （双重验证防止 TOCTOU）                                                  │
│     我们的方案: demote 前检查 → 磁盘空间、并发迁移数、segment 是否正在读取   │
│                                                                             │
│  5. 并发限流保护集群稳定性                                                   │
│     OpenSearch: maxConcurrentTieringRequests 限制同时迁移的索引数             │
│     我们的方案: 需要限制同时迁移的 Segment 数量、单节点迁移带宽              │
│                                                                             │
│  6. 取消操作必须是一等公民                                                   │
│     OpenSearch: cancelTiering 比 tier 优先级更高 (IMMEDIATE > URGENT)        │
│     我们的方案: 允许随时中止 Segment 迁移，回滚到迁移前状态                  │
│                                                                             │
│  7. 前提条件约束清晰                                                         │
│     OpenSearch: 必须启用 Remote Store 才能做 Tiering                         │
│     我们的方案: 必须配置远程存储后端 + 本地缓存空间才能启用 Segment Tiering   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. 本地+远程数据混合检索的实现原理

### 9.1 核心问题

当一个 Shard 中部分 Segment 文件在本地磁盘、部分在远程存储时，Lucene 的 `IndexSearcher` 如何做到**对上层完全透明的统一检索**？

```
问题本质：
┌──────────────────────────────────────────────────────────────────────┐
│  Lucene IndexSearcher                                                │
│  └── IndexReader                                                     │
│       └── LeafReader (per Segment)                                   │
│            └── Directory.openInput(fileName) → IndexInput            │
│                                                                      │
│  Lucene 不关心文件在哪里，只要 Directory.openInput() 返回一个         │
│  能正常 read/seek 的 IndexInput 即可。                                │
│                                                                      │
│  所以混合检索的关键是：                                               │
│  实现一个 Directory，对本地文件返回本地 IndexInput，                   │
│  对远程文件返回带缓存的远程 IndexInput，                              │
│  上层 Lucene 完全无感知。                                             │
└──────────────────────────────────────────────────────────────────────┘
```

### 9.2 ES Frozen Tier 的实现方式

ES 的 Frozen Tier 中，整个索引挂载为 Searchable Snapshot，所有文件都在远程。通过 `SearchableSnapshotDirectory` 统一抽象：

```java
// 源码: SearchableSnapshotDirectory.openInput() (line 369)
// 所有文件都通过远程 + 缓存方式读取

public IndexInput openInput(String name, IOContext context) {
    FileInfo fileInfo = fileInfo(name);  // 文件在快照中的位置元数据

    // Case 1: 极小文件（内容直接存在元数据中）
    if (fileInfo.metadata().hashEqualsContents()) {
        return new ByteArrayIndexInput(content);  // 直接从内存返回
    }

    // Case 2: Frozen Tier（partial = true，部分挂载）
    if (partial) {
        return new FrozenIndexInput(name, this, fileInfo, ...);
        // → 使用 SharedBlobCacheService 做 Region 级缓存
        // → 缓存命中走本地 SSD，miss 走远程 + 异步回填
    }

    // Case 3: Cold Tier（full mount，全量缓存）
    else {
        return new CachedBlobContainerIndexInput(name, this, fileInfo, ...);
        // → 使用 CacheService 做文件级缓存
        // → 整个文件缓存到本地
    }

    // Case 4: 不使用缓存
    return new DirectBlobContainerIndexInput(...);
    // → 每次直接从远程读取
}
```

**关键设计：Lucene 调用 `openInput("_0.si", ctx)` 时不知道数据在哪里，Directory 内部透明处理。**

### 9.3 OpenSearch TieredDirectory + SwitchableIndexInput 的实现（核心创新）

OpenSearch 的方案更接近我们"部分本地、部分远程"的需求。它通过 `TieredDirectory` + `SwitchableIndexInput` 实现了**同一个 Shard 内文件动态切换本地/远程**的能力：

```
┌──────────────────────────────────────────────────────────────────────────┐
│              OpenSearch TieredDirectory 架构                               │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  TieredDirectory extends CompositeDirectory                              │
│  ├── localDirectory (FSDirectory)     → 本地磁盘                         │
│  ├── remoteDirectory (RemoteSegmentStoreDirectory) → 远程对象存储         │
│  ├── fileCache (FileCache)            → LRU 文件缓存                     │
│  └── transferManager                  → 远程文件传输器                    │
│                                                                          │
│  核心创新：SwitchableIndexInput                                           │
│  ┌────────────────────────────────────────────────────────────────┐      │
│  │  一个 IndexInput 内部持有两个引用：                             │      │
│  │  ├── localIndexInput  → 指向本地文件的 IndexInput              │      │
│  │  ├── remoteIndexInput → 指向远程文件的 Block-based IndexInput  │      │
│  │  └── underlyingIndexInput (AtomicReference) → 当前生效的那个    │      │
│  │                                                                │      │
│  │  初始状态：underlyingIndexInput = localIndexInput               │      │
│  │  切换后：  underlyingIndexInput = remoteIndexInput              │      │
│  │                                                                │      │
│  │  所有读操作都委托给 underlyingIndexInput：                      │      │
│  │  readByte() → underlyingIndexInput.get().readByte()            │      │
│  │  seek(pos)  → underlyingIndexInput.get().seek(pos)             │      │
│  │  length()   → underlyingIndexInput.get().length()              │      │
│  └────────────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────┘
```

#### 9.3.1 openInput 流程（混合读取的入口）

```java
// 源码: TieredDirectory.openInput() (line 130)

public IndexInput openInput(String name, IOContext context) {
    // Step 1: 临时文件 → 直接本地读
    if (isTempFile(name)) {
        return localDirectory.openInput(name, context);
    }

    // Step 2: 检查 FileCache 中是否有该文件的 Switchable 条目
    Path key = getFilePathSwitchable(localDirectory, name);
    CachedIndexInput indexInput = fileCache.get(key);

    if (indexInput == null) {
        // Step 3: 本地缓存无 → 检查文件是否在远程存储中
        UploadedSegmentMetadata meta = remoteDirectory.getSegmentsUploadedToRemoteStore().get(name);
        if (meta == null) {
            throw new NoSuchFileException(name);  // 文件不存在
        }
        // Step 4: 文件在远程 → 创建 SwitchableIndexInput（初始指向远程）
        cacheFile(name, true);  // cacheFromRemote = true
        indexInput = fileCache.get(key);
    }

    // Step 5: 返回 Wrapper（对 Lucene 来说就是普通 IndexInput）
    return new SwitchableIndexInputWrapper(
        (SwitchableIndexInput) indexInput.getIndexInput().clone()
    );
}
```

#### 9.3.2 SwitchableIndexInput 切换机制

```java
// 源码: SwitchableIndexInput.switchToRemote() (line 196)
// 当文件从本地上传到远程完成后，可以释放本地全文件，切换为 Block 读取

public void switchToRemote() {
    sharedLock.writeLock().lock();  // 写锁：阻塞所有并发 clone/slice
    try {
        objectLock.lock();  // 实例锁：保护当前对象状态
        try {
            if (isClosed || hasSwitchedToRemote) return;

            // 1. 验证文件确实已在远程
            validateFilePresentInRemote();

            // 2. 获取远程 IndexInput（Block-based，按需加载）
            remoteIndexInput.set(getRemoteIndexInput());

            // 3. 保持当前读取位置
            IndexInput local = underlyingIndexInput.get();
            if (isClone) remoteIndexInput.get().seek(local.getFilePointer());

            // 4. 原子切换：底层引用指向远程
            underlyingIndexInput.set(remoteIndexInput.get());

            // 5. 递归切换所有 clone
            if (!isClone) {
                clones.keySet().forEach(c -> c.switchToRemote());
            }

            // 6. 关闭本地 IndexInput，释放本地文件
            local.close();
            hasSwitchedToRemote = true;
            if (!isClone) fileCache.remove(fullFilePath);
        } finally {
            objectLock.unlock();
        }
    } finally {
        sharedLock.writeLock().unlock();
    }
}
```

**这就是"热转冷"的在线切换：正在被使用的 IndexInput 可以从本地无缝切换到远程，而上层的 IndexSearcher/LeafReader 完全不感知。**

#### 9.3.3 并发安全设计

```
┌──────────────────────────────────────────────────────────────────────────┐
│  SwitchableIndexInput 的锁设计                                            │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  两层锁：                                                                │
│                                                                          │
│  1. sharedLock (ReadWriteLock) — 全实例共享（原始 + 所有 clone）           │
│     ├── 读锁：clone() / slice() 操作获取                                 │
│     │   → 多个查询线程可以同时 clone/slice，互不阻塞                     │
│     └── 写锁：switchToRemote() 操作获取                                   │
│         → 切换时阻塞所有 clone/slice，确保不会遗漏                       │
│                                                                          │
│  2. objectLock (ReentrantLock) — 每个实例独立                             │
│     → 保护单个 IndexInput 实例的内部状态一致性                            │
│                                                                          │
│  为什么需要这样设计？                                                     │
│  ├── IndexSearcher 执行查询时会 clone IndexInput                         │
│  ├── 多个查询线程持有同一文件的不同 clone                                 │
│  ├── switchToRemote 必须同时切换原始 + 所有活跃 clone                     │
│  └── 如果 switch 期间有新 clone 产生，该 clone 会被遗漏（永远指向本地）   │
│                                                                          │
│  所以：switch 拿写锁（独占），clone 拿读锁（共享）→ 不会遗漏              │
└──────────────────────────────────────────────────────────────────────────┘
```

### 9.4 Lucene 层面为什么这能透明工作

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Lucene 检索栈与 Directory 的关系                                         │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  IndexSearcher.search(query)                                             │
│    │                                                                     │
│    ├── for each LeafReaderContext:                                        │
│    │     │                                                               │
│    │     ├── LeafReader (= SegmentReader)                                │
│    │     │     │                                                         │
│    │     │     ├── 读取倒排索引：                                         │
│    │     │     │   Directory.openInput("_0.tim") → IndexInput            │
│    │     │     │   IndexInput.seek(termOffset)                           │
│    │     │     │   IndexInput.readBytes(...)  ← 这里触发实际 I/O         │
│    │     │     │                                                         │
│    │     │     ├── 读取文档值：                                           │
│    │     │     │   Directory.openInput("_0.dvd") → IndexInput            │
│    │     │     │                                                         │
│    │     │     └── 读取存储字段：                                         │
│    │     │         Directory.openInput("_0.fdt") → IndexInput            │
│    │     │                                                               │
│    │     └── 每个 Segment 独立检索，结果合并                              │
│    │                                                                     │
│    └── merge results from all segments                                    │
│                                                                          │
│  关键点：                                                                │
│  - Lucene 对每个 Segment 的每个文件调用 Directory.openInput()             │
│  - 返回的 IndexInput 只需支持 readByte/readBytes/seek/length             │
│  - Lucene 不关心数据来自本地 SSD、内存缓存、还是远程 S3                  │
│  - 只要 IndexInput 接口正确实现，检索逻辑完全不变                        │
│                                                                          │
│  混合检索 = 不同 Segment 文件的 openInput() 返回不同实现的 IndexInput     │
│  ├── 热 Segment 文件 → 本地 FSIndexInput（直接磁盘读）                   │
│  ├── 冷 Segment 文件 → FrozenIndexInput / SwitchableIndexInput（远程+缓存）│
│  └── IndexSearcher 完全不感知差异                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

### 9.5 两种实现模式对比

```
┌──────────────────────────────────────────────────────────────────────────┐
│  模式 A: ES Frozen Tier（整索引远程）                                     │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  SearchableSnapshotDirectory                                             │
│  └── openInput(任何文件) → FrozenIndexInput                              │
│       └── SharedBlobCacheService.populateAndRead()                        │
│            ├── 缓存命中 → 从本地 SSD Region 读取（fast-path）             │
│            └── 缓存 miss → 从远程拉取 range → 写入缓存 → 返回            │
│                                                                          │
│  特点：                                                                  │
│  - 整个索引的所有文件都走同一个读取路径                                   │
│  - 缓存粒度：Range/Region (16MB)                                         │
│  - 无需"切换"概念，始终是"远程 + 缓存"                                  │
│  - 适合：数据已经确定为冷数据的场景                                       │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  模式 B: OpenSearch TieredDirectory（同一 Shard 内文件动态切换）           │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  TieredDirectory                                                         │
│  └── openInput(文件名) → SwitchableIndexInput                            │
│       ├── 初始状态（文件在本地）:                                         │
│       │   underlyingIndexInput → localDirectory.openInput()               │
│       │   读取走本地磁盘，性能等同原始 ES                                 │
│       │                                                                   │
│       └── 切换后（文件已上传远程，本地可释放）:                            │
│           underlyingIndexInput → OnDemandBlockSnapshotIndexInput           │
│           读取走远程 Block + FileCache                                     │
│                                                                          │
│  特点：                                                                  │
│  - 同一 Shard 内，有的文件走本地，有的走远程                              │
│  - 支持在线切换：文件上传完远程后，动态从本地切到远程                     │
│  - 切换过程中正在进行的查询不会中断（原子引用切换）                       │
│  - 适合：同一索引内有冷热混合的场景                                       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 9.6 文件列表的合并（listAll）

混合检索还需要解决"索引里到底有哪些文件"的问题：

```java
// 源码: TieredDirectory.listAll() (line 66)

public String[] listAll() {
    String[] localFiles = localDirectory.listAll();    // 本地文件列表
    String[] remoteFiles = remoteDirectory.listAll();  // 远程文件列表

    // 如果本地有 segments_N 文件（commit point），以本地为准
    // 避免远程的旧 segments_N 覆盖本地新写入的
    if (hasLocalSegments) {
        remoteFiles = filter out segments_N from remote;
    }

    // 合并去重 → 返回完整文件列表
    return concat(localFiles, remoteFiles).distinct().sorted();
}

// 对 Lucene 来说，listAll() 返回的就是 Shard 中所有可用的文件
// SegmentInfos 读取 segments_N 后，知道哪些 Segment 存在
// 然后对每个 Segment 的每个文件调用 openInput()
// 不管文件在本地还是远程，openInput 都能正确返回可读的 IndexInput
```

### 9.7 远程文件的 Block 级读取

当 SwitchableIndexInput 切换到远程后，实际读取由 `OnDemandBlockSnapshotIndexInput` 完成：

```java
// 源码: OnDemandBlockSnapshotIndexInput.fetchBlock() (line 143)

protected IndexInput fetchBlock(int blockId) {
    String blockFileName = getBlockFileName(fileName, blockId);  // e.g. _0.cfs_block_3

    long blockStart = getBlockStart(blockId);    // blockId * blockSize
    long blockEnd = blockStart + getActualBlockSize(blockId);

    // 可能跨越多个远程 blob part（大文件会被分片存储）
    List<BlobPart> blobParts = getBlobParts(blockStart, blockEnd);

    // 通过 TransferManager 从远程下载 block → 写入本地缓存
    BlobFetchRequest request = BlobFetchRequest.builder()
        .blobParts(blobParts)
        .directory(localFSDirectory)   // 缓存写入本地
        .fileName(blockFileName)
        .build();

    return transferManager.fetchBlob(request);
    // 返回指向本地缓存文件的 IndexInput
    // 下次读同一 block 直接从 FileCache 获取
}
```

### 9.8 性能影响与优化策略

```
┌──────────────────────────────────────────────────────────────────────────┐
│  混合检索场景的性能分析                                                    │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  查询场景        │ 数据位置      │ 延迟         │ 优化手段                │
│  ─────────────── │ ───────────── │ ──────────── │ ──────────────────────  │
│  热 Segment 查询  │ 本地 SSD     │ μs 级        │ 无需优化，原始性能       │
│  缓存命中的冷查询│ 本地缓存 SSD │ ms 级        │ 预热缓存、合理 range    │
│  缓存 miss 的冷查询│ 远程存储   │ 10-100ms     │ prefetch、增大缓存      │
│  首次加载冷 Segment│ 远程存储  │ 100ms-数秒   │ recovery range 小粒度   │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  优化策略                                                                │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. Prefetch（预取）                                                      │
│     - OpenSearch: TieredStoragePrefetchSettings 控制预取策略              │
│     - 读取一个 block 时异步预取后续 N 个 block                            │
│     - 因为 Lucene 的倒排列表是顺序存储的                                 │
│                                                                          │
│  2. 缓存预热（Prewarm）                                                   │
│     - ES: SNAPSHOT_CACHE_PREWARM_ENABLED_SETTING                         │
│     - Shard 恢复完成后，后台预加载常用文件到缓存                          │
│     - 减少首次查询的 miss 率                                              │
│                                                                          │
│  3. 文件类型感知                                                          │
│     - .tip/.tim (倒排索引) → 高优先级缓存，查询必读                      │
│     - .dvd/.dvm (doc values) → 聚合查询必读                              │
│     - .fdt/.fdx (stored fields) → 仅 _source 获取时读                   │
│     - .pos/.pay (positions) → 仅短语查询时读                             │
│                                                                          │
│  4. 查询路由优化                                                          │
│     - 查询时如果知道某些 Segment 全在远程，可设置更长超时                 │
│     - 或在 Coordinator 层对冷 Shard 降低并发度                            │
│     - 避免大量冷查询打爆远程存储带宽                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

### 9.9 总结：混合检索的分层抽象

```
┌──────────────────────────────────────────────────────────────────────────┐
│  混合检索能够透明工作的根本原因：Lucene Directory 抽象                     │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Layer 1: Lucene IndexSearcher                                           │
│  └── 只知道"搜索 query，返回结果"                                        │
│       不关心存储位置                                                      │
│                                                                          │
│  Layer 2: Lucene SegmentReader                                           │
│  └── 只知道"通过 Directory 打开文件，用 IndexInput 读数据"                │
│       不关心 IndexInput 的具体实现                                        │
│                                                                          │
│  Layer 3: Directory 实现 (SearchableSnapshotDirectory / TieredDirectory) │
│  └── 决定"这个文件从哪里读"                                              │
│       返回适当的 IndexInput 实现                                          │
│                                                                          │
│  Layer 4: IndexInput 实现                                                │
│  ├── FrozenIndexInput       → SharedBlobCache + 远程                     │
│  ├── SwitchableIndexInput   → 本地 ↔ 远程 动态切换                       │
│  ├── OnDemandBlockInput     → 按 Block 从远程拉取                        │
│  └── FSIndexInput           → 普通本地磁盘读取                           │
│                                                                          │
│  Layer 5: 实际存储                                                       │
│  ├── 本地 SSD                                                            │
│  ├── 本地缓存 (SharedBlobCache / FileCache)                              │
│  └── 远程对象存储 (S3 / OSS / GCS)                                      │
│                                                                          │
│  结论：                                                                  │
│  混合检索 = Directory 层的多态 + IndexInput 层的缓存策略                   │
│  无需修改 Lucene 搜索逻辑，只需在 Directory 层做正确的路由和缓存           │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Remote Store 全量远程 + 本地缓存（存算分离终态）详解

### 10.1 核心设计理念

```
┌──────────────────────────────────────────────────────────────────────────┐
│  传统模式 vs Remote Store 模式                                            │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  传统模式：本地存储是 Source of Truth                                     │
│  ┌─────────────────────────────────────────────┐                         │
│  │  写入 → 本地 Lucene Segment → 本地持久化     │                         │
│  │  副本同步 → Document Replication（文档重放）  │                         │
│  │  数据安全 → 依赖本地磁盘 + 副本冗余          │                         │
│  └─────────────────────────────────────────────┘                         │
│                                                                          │
│  Remote Store 模式：远程存储是 Source of Truth                            │
│  ┌─────────────────────────────────────────────┐                         │
│  │  写入 → 本地 Lucene Segment → 上传远程存储   │  ← write-through        │
│  │  副本同步 → Segment Replication（从远程拉取）│  ← 无需主分片参与        │
│  │  数据安全 → 依赖远程存储（S3 11个9 持久性）  │                         │
│  │  本地存储 → 仅作为写入缓冲 + 读取缓存       │  ← 可随时丢弃重建        │
│  └─────────────────────────────────────────────┘                         │
│                                                                          │
│  为什么是"存算分离终态"：                                                │
│  - 计算节点完全无状态（本地数据可丢失，从远程恢复）                       │
│  - 扩缩容不需要数据迁移（新节点从远程加载即可服务）                       │
│  - 存储和计算独立扩展（存储按量计费，计算按需伸缩）                       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.2 写入路径：RemoteStoreRefreshListener

**源码位置：** `server/src/main/java/org/opensearch/index/shard/RemoteStoreRefreshListener.java`

这是 Remote Store 最核心的组件 — 一个挂载在 IndexShard 上的 RefreshListener，每次 refresh（新 Segment 生成）后自动将新文件上传到远程：

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 Write-Through 数据流                                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  客户端写入请求                                                           │
│       │                                                                   │
│       ▼                                                                   │
│  ┌──────────────┐                                                        │
│  │ InternalEngine│  → 写入 Translog（本地 + Remote Translog）             │
│  │   .index()   │  → 写入 IndexWriter Buffer                             │
│  └──────┬───────┘                                                        │
│         │                                                                │
│         │  定时 refresh（默认 1s）                                         │
│         ▼                                                                │
│  ┌──────────────────────┐                                                │
│  │  Lucene Refresh       │                                               │
│  │  IndexWriter.refresh()│  → 生成新的 Segment 文件到本地磁盘             │
│  └──────┬───────────────┘                                                │
│         │                                                                │
│         │  触发 RefreshListener                                           │
│         ▼                                                                │
│  ┌──────────────────────────────────────────────────────────┐            │
│  │  RemoteStoreRefreshListener.performAfterRefreshWithPermit │            │
│  │                                                          │            │
│  │  1. shouldSync() 判断是否需要上传：                       │            │
│  │     - readers 是否变化（新 Segment 生成）                 │            │
│  │     - 远程存储是否已同步                                  │            │
│  │     - 是否 commit 后的首次 refresh                        │            │
│  │                                                          │            │
│  │  2. syncSegments() 执行上传：                             │            │
│  │     a. 获取 CatalogSnapshot（当前所有 Segment 文件列表）  │            │
│  │     b. 过滤出未上传的新文件                               │            │
│  │     c. 并行上传到 RemoteSegmentStoreDirectory             │            │
│  │     d. 上传完成后写入 metadata 文件                       │            │
│  │     e. 发布 ReplicationCheckpoint                         │            │
│  │                                                          │            │
│  │  3. 失败重试：                                            │            │
│  │     - 指数退避：1s → 2s → 4s → ... → max 10s            │            │
│  │     - 不影响写入（异步上传，不阻塞 refresh）              │            │
│  └──────────────────────────────────────────────────────────┘            │
│         │                                                                │
│         ▼                                                                │
│  ┌──────────────────────────────────────────┐                            │
│  │  Remote Segment Store (S3/OSS/GCS)        │                           │
│  │  ├── segments/data/  (Segment 文件)       │                           │
│  │  │   └── _0.cfe__<uuid>                  │  ← 带 UUID 防覆盖         │
│  │  └── segments/metadata/ (元数据)          │                           │
│  │      └── metadata__<primary_term>__<gen>  │  ← 记录哪些文件已上传     │
│  └──────────────────────────────────────────┘                            │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.3 上传实现：RemoteStoreUploaderService

```java
// 源码: RemoteStoreUploaderService.uploadSegments() (line 83)

public void uploadSegments(Collection<String> localSegments, ...) {
    // 对每个新 Segment 文件并行上传
    for (String localSegment : localSegments) {
        // 1. 调用 RemoteSegmentStoreDirectory.copyFrom
        //    将本地文件复制到远程存储
        remoteDirectory.copyFrom(
            storeDirectory,         // 源：本地 FSDirectory
            localSegment,           // 文件名：如 _0.cfe
            IOContext.DEFAULT,
            aggregatedListener,     // 完成回调
            isLowPriorityUpload,    // 低优先级上传（后台）
            cryptoMetadata          // 可选加密
        );

        // 2. 上传成功后通知 syncListeners
        // → TieredDirectory.afterSyncToRemote(file)
        //   → SwitchableIndexInput.switchToRemote()
        //   → 释放本地全文件，切换为 Block 读取模式
        notifyAfterSyncToRemote(localSegment);
    }
}
```

**关键点：`notifyAfterSyncToRemote` 是连接"写入上传"和"读取切换"的桥梁。**

### 10.4 Segment 文件的命名与防覆盖

```
┌──────────────────────────────────────────────────────────────────────────┐
│  远程存储中的 Segment 文件命名                                            │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  本地文件名:  _0.cfe                                                     │
│  远程文件名:  _0.cfe__gX7bNIIBrs0AUNsR2yEG                              │
│                    ↑                                                      │
│                    SEGMENT_NAME_UUID_SEPARATOR = "__"                     │
│                    后缀为随机 UUID                                         │
│                                                                          │
│  为什么需要 UUID 后缀？                                                   │
│  - 防止 split-brain 场景下两个 primary 上传同名文件互相覆盖               │
│  - 保证远程存储中每个文件都是唯一的                                       │
│  - RemoteSegmentStoreDirectory 维护 local_name → remote_name 映射        │
│                                                                          │
│  远程存储目录结构：                                                       │
│  <cluster_uuid>/<index_uuid>/<shard_id>/                                 │
│  ├── segments/                                                           │
│  │   ├── data/                                                           │
│  │   │   ├── _0.cfe__<uuid1>                                            │
│  │   │   ├── _0.si__<uuid2>                                             │
│  │   │   ├── _0_Lucene90_0.dvd__<uuid3>                                 │
│  │   │   └── ...                                                         │
│  │   └── metadata/                                                       │
│  │       ├── metadata__3__7__<uuid>  (primary_term=3, gen=7)             │
│  │       └── metadata__3__8__<uuid>  (最新)                              │
│  └── translog/                                                           │
│      ├── data/                                                           │
│      └── metadata/                                                       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.5 副本同步：Segment Replication via Remote Store

```
┌──────────────────────────────────────────────────────────────────────────┐
│  传统 Document Replication vs Remote Store Segment Replication            │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  传统（ES 默认）：                                                        │
│  Primary → index doc → 转发 doc 给 Replica → Replica 独立 index          │
│  问题：副本 CPU = 主分片 CPU × 副本数                                    │
│                                                                          │
│  Remote Store Segment Replication：                                       │
│  Primary → index doc → refresh → 上传 Segment 到 Remote Store            │
│         → 发布 ReplicationCheckpoint                                      │
│  Replica → 收到 Checkpoint → 从 Remote Store 下载新 Segment → 加载       │
│                                                                          │
│  流程图：                                                                │
│                                                                          │
│  Primary                          Remote Store              Replica       │
│     │                                  │                       │          │
│     │── 写入 + refresh ──▶            │                       │          │
│     │── 上传 Segment files ─────────▶ │                       │          │
│     │── 上传 metadata ──────────────▶ │                       │          │
│     │── 发布 Checkpoint ─────────────────────────────────────▶│          │
│     │                                  │                       │          │
│     │                                  │ ◀── 下载新 Segment ──│          │
│     │                                  │                       │          │
│     │                                  │    加载到 IndexReader  │          │
│     │                                  │    查询可见            │          │
│                                                                          │
│  优势：                                                                  │
│  1. 副本零 CPU：不做 indexing，仅下载 + 加载文件                          │
│  2. 主节点零负担：副本从远程拉，不消耗主节点网络/磁盘                     │
│  3. 扩副本秒级生效：新副本从远程下载 Segment 即可服务                     │
│  4. 一致性保证：Checkpoint 包含 seqNo，确保因果序                         │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.6 节点恢复：从远程重建

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Remote Store 模式下的节点恢复                                            │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  传统恢复（无 Remote Store）：                                            │
│  1. 新节点启动                                                           │
│  2. 从 Primary 做 Peer Recovery（拉取所有 Segment 文件）                  │
│  3. 回放 Translog（追赶最新数据）                                         │
│  → 耗时：分钟到小时级（取决于数据量）                                    │
│                                                                          │
│  Remote Store 恢复：                                                      │
│  1. 新节点启动                                                           │
│  2. 读取远程 metadata 文件 → 获取最新 Segment 列表                       │
│  3. 从远程存储下载 Segment 文件（或仅加载元数据，按需读取）               │
│  4. 回放 Remote Translog 中未 flush 的操作                                │
│  → 耗时：秒到分钟级（仅下载元数据 + 增量 translog）                      │
│                                                                          │
│  Warm 节点恢复（更快）：                                                  │
│  1. 新节点启动                                                           │
│  2. 读取远程 metadata → 获取 Segment 列表                                │
│  3. 不下载 Segment 文件，仅建立 FileCache 映射                           │
│  4. 查询时按需从远程加载（Block-based）                                   │
│  → 耗时：秒级（几乎零数据传输）                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.7 write-through 模式的数据一致性保障

```
┌──────────────────────────────────────────────────────────────────────────┐
│  数据持久性保障机制                                                        │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  问题：如果 Segment 上传到远程之前节点挂了，数据会丢吗？                  │
│                                                                          │
│  答案：不会。通过 Remote Translog 保证。                                  │
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────┐       │
│  │  写入顺序：                                                    │       │
│  │  1. 写入 Translog（同步刷盘 / 上传 Remote Translog）           │       │
│  │  2. 写入 IndexWriter Buffer（内存）                            │       │
│  │  3. refresh → 生成 Segment（本地磁盘）                         │       │
│  │  4. 异步上传 Segment 到 Remote Store                           │       │
│  │                                                                │       │
│  │  如果步骤 4 之前 crash：                                       │       │
│  │  → 恢复时从 Remote Translog 回放 → 重新生成 Segment → 重新上传│       │
│  │                                                                │       │
│  │  如果步骤 4 部分完成（部分文件上传）：                          │       │
│  │  → metadata 未写入 → 远程看不到这批文件                        │       │
│  │  → 下次 refresh 时重新上传（幂等：UUID 确保不重复）            │       │
│  └───────────────────────────────────────────────────────────────┘       │
│                                                                          │
│  一致性点 = metadata 文件写入成功：                                       │
│  - metadata 原子写入（单个对象 PUT）                                      │
│  - 包含所有已确认上传的 Segment 文件列表                                  │
│  - 副本读取 metadata 后能看到一致的视图                                   │
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────┐       │
│  │  RemoteStoreRefreshListener.syncSegments() 的原子性：          │       │
│  │                                                                │       │
│  │  uploadNewSegments(files)           // 上传数据文件             │       │
│  │       ↓ 全部成功后                                             │       │
│  │  uploadMetadata(segmentInfos)       // 原子写入 metadata       │       │
│  │       ↓ 成功后                                                 │       │
│  │  publishCheckpoint()                // 通知副本有新数据         │       │
│  │                                                                │       │
│  │  如果 uploadNewSegments 部分失败 → metadata 不写入 → 对外不可见│       │
│  │  如果 metadata 写入失败 → checkpoint 不发布 → 副本不感知       │       │
│  └───────────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.8 为什么是存算分离的终态

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Remote Store 模式下的弹性架构优势                                        │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. 计算节点完全无状态                                                    │
│  ┌────────────────────────────────────────────────────────────┐          │
│  │  - 本地磁盘仅用于写入缓冲（Translog + 未上传的 Segment）  │          │
│  │  - 本地数据全部丢失 → 从远程 Translog + Segment 恢复     │          │
│  │  - 节点重启后秒级恢复到可服务状态                          │          │
│  │  - 扩容新节点：启动 → 加载元数据 → 立即接受查询（按需加载）│          │
│  └────────────────────────────────────────────────────────────┘          │
│                                                                          │
│  2. 存储与计算独立扩展                                                    │
│  ┌────────────────────────────────────────────────────────────┐          │
│  │  存储扩展：数据增长 → 远程存储自动扩容（S3 无限容量）      │          │
│  │  计算扩展：查询负载增 → 加计算节点（从远程读 Segment）      │          │
│  │  两者解耦：不需要"加存储时必须加计算"的浪费                 │          │
│  └────────────────────────────────────────────────────────────┘          │
│                                                                          │
│  3. 分片迁移零数据拷贝                                                    │
│  ┌────────────────────────────────────────────────────────────┐          │
│  │  传统：迁移分片 = 拷贝 GB~TB 级数据，耗时数分钟到数小时    │          │
│  │  Remote Store：迁移分片 = 更新路由表 + 新节点读远程数据     │          │
│  │  → 实际数据不移动，只是"谁来读"发生变化                     │          │
│  │  → 扩缩容从数据密集型操作变成纯元数据操作                   │          │
│  └────────────────────────────────────────────────────────────┘          │
│                                                                          │
│  4. 副本扩展无需主分片参与                                                │
│  ┌────────────────────────────────────────────────────────────┐          │
│  │  传统：加副本 = 主分片拷贝所有数据给新副本（Peer Recovery）  │          │
│  │  Remote Store：加副本 = 新节点从远程存储拉 Segment          │          │
│  │  → 主节点零额外负载                                         │          │
│  │  → 可以在搜索高峰时安全扩副本而不影响写入性能               │          │
│  └────────────────────────────────────────────────────────────┘          │
│                                                                          │
│  5. 成本优化                                                              │
│  ┌────────────────────────────────────────────────────────────┐          │
│  │  - 远程存储：S3 标准 $0.023/GB/月 vs SSD $0.1/GB/月        │          │
│  │  - 副本存储成本 → 0（副本从远程读，不存本地）               │          │
│  │  - 冷数据节点 → 小 SSD 缓存 + 大远程存储（而非大 HDD）     │          │
│  └────────────────────────────────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.9 性能权衡与适用场景

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Remote Store 的性能代价                                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  代价 1: 写入延迟增加                                                     │
│  ├── refresh 后需要上传 Segment 到远程（异步，不阻塞 refresh 本身）       │
│  ├── 但 Translog 需要同步写远程（fsync → remote PUT）                    │
│  └── 权衡：Remote Translog 可配置为异步或同步                             │
│                                                                          │
│  代价 2: 副本可见性延迟                                                   │
│  ├── 传统：Primary refresh → Replica 几乎同时可见                        │
│  ├── Remote Store：Primary refresh → 上传 → 副本下载 → 可见              │
│  └── 额外延迟：通常数十到数百毫秒                                        │
│                                                                          │
│  代价 3: 冷查询延迟                                                       │
│  ├── 首次查询未缓存的 Segment → 从远程拉取                               │
│  ├── 延迟 10-100ms（vs 本地 SSD < 1ms）                                  │
│  └── 缓存预热后与本地性能一致                                            │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  适用场景判断                                                             │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  强烈推荐：                                                              │
│  ├── 日志/可观测性（写多读少，数据量大）                                  │
│  ├── 弹性伸缩需求高的业务（电商大促）                                    │
│  ├── 多副本场景（副本存储成本降到接近零）                                 │
│  └── 灾备要求高（远程存储天然多 AZ 冗余）                                │
│                                                                          │
│  需要评估：                                                              │
│  ├── 实时搜索（对副本可见性延迟敏感）                                    │
│  ├── 高吞吐写入（Remote Translog 可能成为瓶颈）                          │
│  └── 延迟敏感型查询（P99 要求 < 10ms）                                   │
│                                                                          │
│  不推荐：                                                                │
│  ├── 无远程存储基础设施的纯私有化部署                                    │
│  └── 数据量极小但对延迟极度敏感的场景                                    │
└──────────────────────────────────────────────────────────────────────────┘
```

### 10.10 与 Segment 级分层方案的关系

```
Remote Store 和 Segment 级分层不是互斥的，而是互补的：

┌──────────────────────────────────────────────────────────────────────────┐
│  架构演进路线                                                             │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Phase 1: ILM + 索引级分层（当前可用）                                    │
│  └── 时序数据按时间整索引搬迁                                            │
│                                                                          │
│  Phase 2: Remote Store（全量远程）                                        │
│  └── 所有 Segment 都上传远程，本地仅缓存                                 │
│  └── 解决了"扩缩容必须迁移数据"的核心痛点                                │
│                                                                          │
│  Phase 3: Remote Store + Segment 级缓存策略                               │
│  └── 在 Remote Store 基础上，本地缓存策略按 Segment 热度差异化：          │
│      ├── 热 Segment → 常驻本地缓存（pin in cache）                       │
│      ├── 温 Segment → 正常 LFU 淘汰                                     │
│      └── 冷 Segment → 不缓存（每次从远程读取）                           │
│  └── 这就是我们 Segment 级分层策略的"轻量版"                              │
│      不需要在文件层面做 hot/cold 划分，                                   │
│      只需要在缓存层面做优先级管理                                         │
│                                                                          │
│  总结：                                                                  │
│  Remote Store = 存算分离的基础设施层                                      │
│  Segment Tiering = 在此基础上的缓存/性能优化层                            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 11. 电商大促场景下的弹性伸缩落地方案

### 11.1 电商大促的流量特征

```
┌──────────────────────────────────────────────────────────────────────────┐
│  流量模型                                                                 │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  QPS                                                                     │
│   │       ┌──────┐                                                       │
│   │       │ 大促 │ ← 峰值 10-100x 日常                                  │
│   │       │ 高峰 │                                                       │
│   │    ┌──┤      ├──┐                                                    │
│   │    │  │      │  │  ← 预热期/余热期                                   │
│   │ ───┘  └──────┘  └─── ← 日常水位                                     │
│   └────────────────────────────── 时间                                   │
│        -2h  0h    4h  +6h                                                │
│                                                                          │
│  特征：                                                                  │
│  ├── 搜索 QPS：日常 5K → 大促 50K-500K                                   │
│  ├── 写入 TPS：日常 2K → 大促 20K-50K（库存、价格、状态变更）             │
│  ├── 峰值持续：2-6 小时（集中在 0点/10点/20点）                           │
│  ├── 预热期：大促前 1-2 小时流量逐步攀升                                  │
│  └── 关键约束：搜索 P99 < 200ms，不允许降级                              │
└──────────────────────────────────────────────────────────────────────────┘
```

### 11.2 传统架构的痛点

```
传统固定集群应对大促：

方案 A: 按峰值预留资源
├── 日常 5 节点足够，为大促预留 50 节点
├── 日常资源利用率 < 10%
├── 年化成本浪费 80%+
└── 且无法应对超预期流量

方案 B: 大促前手动扩容
├── 提前 1-2 天扩容 → 分片迁移需要数小时
├── 扩容过程中分片 rebalance 影响线上性能
├── 缩容时同样需要数小时数据迁移
├── 运维人员需要大促期间值守
└── 扩容过程出问题的风险高
```

### 11.3 Remote Store 架构下的大促弹性方案

```
┌──────────────────────────────────────────────────────────────────────────┐
│  核心思路：分离"写入节点"和"搜索节点"，搜索节点秒级弹性                    │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  常驻层（固定，不伸缩）                                          │   │
│  │  ├── Master 节点 × 3（集群管理）                                  │   │
│  │  ├── 写入节点 × N（Primary 分片，负责 index + refresh + 上传远程）│   │
│  │  └── 远程存储（S3/OSS，无限容量）                                 │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  弹性层（按需伸缩）                                              │   │
│  │  ├── 搜索节点 × M（Replica 分片，只处理搜索请求）                 │   │
│  │  │   - 从 Remote Store 加载 Segment（无需 Primary 参与）          │   │
│  │  │   - 本地 SSD 作为 FileCache（热点数据常驻）                    │   │
│  │  │   - 可在秒级内加入集群开始服务                                 │   │
│  │  │                                                               │   │
│  │  └── 日常 M=5 → 大促 M=50（搜索节点 10 倍扩展）                  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  为什么搜索节点能秒级扩容？                                              │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  1. 无数据迁移：Segment 文件在远程存储，新节点从远程读即可         │   │
│  │  2. 无 Peer Recovery：不需要从 Primary 拷贝数据                   │   │
│  │  3. 仅需加载元数据：读取 metadata 文件知道 Segment 列表           │   │
│  │  4. 按需加载内容：首次查询时从远程拉取需要的 Segment Block        │   │
│  │  5. FileCache 预热：启动后自动预热高频 Segment（可选）            │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

### 11.4 大促全生命周期操作手册

```
┌──────────────────────────────────────────────────────────────────────────┐
│  T-24h：大促前一天                                                        │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  操作：预热搜索节点缓存                                                   │
│                                                                          │
│  1. 预扩部分搜索节点（如 5 → 15）                                         │
│     → 让新节点从远程加载 Segment 元数据                                   │
│     → 后台预热 FileCache（高频商品索引的 .tim/.tip 文件）                 │
│                                                                          │
│  2. 确认写入节点容量充足                                                  │
│     → 大促期间价格/库存变更频率上升                                       │
│     → 如需扩容写入节点，提前做（因为 Primary 迁移较慢）                   │
│                                                                          │
│  3. 调整 ILM 策略                                                         │
│     → 暂停大促期间的 rollover（避免高峰期间触发分片迁移）                  │
│     → 暂停 forcemerge（避免抢占 I/O）                                    │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  T-2h：大促前两小时                                                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  操作：扩容搜索节点到峰值规模                                             │
│                                                                          │
│  1. 触发扩容：搜索节点 15 → 50                                            │
│     方式 A: K8s HPA 基于自定义指标自动触发                                │
│     方式 B: 定时任务预扩容                                                │
│     方式 C: 手动触发 API                                                  │
│                                                                          │
│  2. 新搜索节点启动流程（每个节点）：                                      │
│     ┌──────────────────────────────────────────────────────────┐         │
│     │  0s   : K8s 调度 Pod，ES 进程启动                        │         │
│     │  5-10s: 节点加入集群，读取 ClusterState                  │         │
│     │  10-15s: 分片分配，加载远程 Segment 元数据               │         │
│     │  15-30s: FileCache 预热启动（后台）                       │         │
│     │  30s  : 开始接受搜索请求（未缓存数据从远程按需加载）      │         │
│     │  5min : 热点数据缓存完成，达到稳态性能                    │         │
│     └──────────────────────────────────────────────────────────┘         │
│                                                                          │
│  3. 验证：                                                                │
│     → 灰度切入少量搜索流量到新节点                                       │
│     → 确认 P99 延迟在 SLA 内                                             │
│     → 确认无异常错误                                                     │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  T=0：大促进行中                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  自动伸缩策略（AutoScaler 持续运行）：                                    │
│                                                                          │
│  监控指标：                                                               │
│  ├── 搜索延迟 P99                                                        │
│  ├── 搜索线程池队列长度                                                  │
│  ├── 搜索节点 CPU 使用率                                                 │
│  └── FileCache 命中率                                                    │
│                                                                          │
│  扩容规则：                                                               │
│  if (search_p99 > 150ms && duration > 2min) → +20% 搜索节点              │
│  if (search_thread_pool_queue > 100 && duration > 1min) → +10% 搜索节点  │
│  if (cpu > 75% && duration > 3min) → +20% 搜索节点                       │
│                                                                          │
│  缩容规则（保守）：                                                       │
│  if (cpu < 30% && search_p99 < 50ms && duration > 15min) → -10% 搜索节点 │
│  cooldown: 10min（两次缩容之间至少间隔 10 分钟）                          │
│  下限: 不低于日常节点数的 2 倍（防止缩容过度导致二次扩容）                │
│                                                                          │
│  写入节点保护：                                                           │
│  ├── 写入节点不参与搜索负载均衡（仅处理 index 请求）                     │
│  ├── 搜索流量全部路由到搜索节点                                          │
│  └── 写入节点 refresh interval 可临时调大（如 5s）降低远程上传频率        │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  T+6h：大促结束                                                           │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  操作：渐进式缩容                                                         │
│                                                                          │
│  1. 等待流量回落到日常水位（观察 30min）                                   │
│                                                                          │
│  2. 渐进缩容搜索节点：50 → 30 → 15 → 5                                  │
│     ├── 每次缩容 30%，间隔 15min                                         │
│     ├── 缩容前确认剩余节点能承载当前负载                                 │
│     ├── 缩容节点的分片自动由其他节点从远程重新加载                        │
│     └── 无数据丢失风险（数据在远程存储，节点只是缓存）                    │
│                                                                          │
│  3. 恢复 ILM 策略                                                         │
│     → 重新启用 rollover、forcemerge                                      │
│     → 恢复正常 refresh interval                                          │
│                                                                          │
│  4. 缩容节点释放：                                                        │
│     ├── 节点退出集群 → 其分片标记为 unassigned                            │
│     ├── 集群将分片分配给剩余搜索节点                                     │
│     ├── 剩余节点从远程加载（已缓存的无需重新加载）                        │
│     └── K8s 释放 Pod 资源                                                │
└──────────────────────────────────────────────────────────────────────────┘
```

### 11.5 关键技术实现

#### 11.5.1 搜索节点快速启动的 FileCache 预热

```
预热策略（按优先级）：

1. 索引级预热
   → 大促核心索引（商品、搜索联想、推荐）标记为 "prewarm: true"
   → 新节点启动后优先加载这些索引的 Segment

2. 文件类型优先级
   → .tip/.tim（Term Index / Term Dictionary）最先加载 ← 搜索必读
   → .doc/.pos（Postings）次优先 ← 匹配文档列表
   → .dvd/.dvm（DocValues）按需 ← 仅排序/聚合时读
   → .fdt/.fdx（Stored Fields）最低 ← 仅 _source 获取时读

3. 热度感知预热
   → 基于历史查询日志统计高频 Segment
   → 优先预热命中率高的 Segment
   → 长尾 Segment 不预热（按需加载）

4. 预热带宽控制
   → 预热不能打爆远程存储带宽
   → 限制单节点预热速率（如 200MB/s）
   → 预热优先级低于实时查询的远程加载
```

#### 11.5.2 搜索/写入节点分离的路由设计

```yaml
# ES 节点角色配置

# 写入节点（固定）
node.roles: ["data_hot", "ingest"]
# 仅持有 Primary 分片，负责 index + 上传远程

# 搜索节点（弹性）
node.roles: ["data_warm", "search"]  # 或自定义 search 角色
# 仅持有 Replica 分片，负责搜索
# 从 Remote Store 加载数据

# 路由策略
index.routing.allocation.require.node_role: "data_hot"  # Primary 分配到写入节点
# Replica 通过 allocation awareness 分配到搜索节点
```

```
请求路由：
┌────────────┐     搜索请求     ┌────────────────┐
│  客户端    │ ──────────────▶  │ Coordinator    │
└────────────┘                  │ （可以是搜索节点）│
                                └────────┬───────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
            ┌──────────────┐   ┌──────────────┐    ┌──────────────┐
            │ Search Node 1│   │ Search Node 2│    │ Search Node N│
            │ (Replica)    │   │ (Replica)    │    │ (Replica)    │
            └──────────────┘   └──────────────┘    └──────────────┘
                    ↑                    ↑                    ↑
                    └────────────────────┴────────────────────┘
                              从 Remote Store 按需加载

┌────────────┐     写入请求     ┌────────────────┐
│  客户端    │ ──────────────▶  │ Ingest Node    │
└────────────┘                  └────────┬───────┘
                                         │
                    ┌────────────────────┼────────────────┐
                    ▼                    ▼                ▼
            ┌──────────────┐   ┌──────────────┐  ┌──────────────┐
            │ Write Node 1 │   │ Write Node 2 │  │ Write Node 3 │
            │ (Primary)    │   │ (Primary)    │  │ (Primary)    │
            └──────┬───────┘   └──────┬───────┘  └──────┬───────┘
                   │                   │                  │
                   └───────────────────┴──────────────────┘
                              上传 Segment 到 Remote Store
```

#### 11.5.3 大促期间的降级预案

```
┌──────────────────────────────────────────────────────────────────────────┐
│  降级预案（按严重程度递进）                                                │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Level 1: 缓存未预热完成，冷查询延迟高                                    │
│  ├── 动作：扩大搜索超时（200ms → 500ms）                                 │
│  ├── 动作：临时允许返回部分结果（allow_partial_search_results: true）     │
│  └── 影响：少量查询变慢，无数据丢失                                      │
│                                                                          │
│  Level 2: 远程存储带宽打满                                                │
│  ├── 动作：限制新节点预热速率                                            │
│  ├── 动作：增加本地缓存 TTL（减少淘汰频率）                              │
│  └── 动作：搜索请求加 preference=_local 优先本地缓存                     │
│                                                                          │
│  Level 3: 搜索节点扩容速度跟不上流量增长                                  │
│  ├── 动作：接入限流（按用户/IP 限制 QPS）                                │
│  ├── 动作：关闭低优先级搜索功能（如联想词、相关推荐）                    │
│  └── 动作：降低搜索结果精度（减少 fetch 的文档数）                       │
│                                                                          │
│  Level 4: Remote Store 不可用                                             │
│  ├── 动作：搜索节点降级为纯缓存模式（只搜已缓存数据）                    │
│  ├── 动作：写入节点暂停远程上传，纯本地模式                              │
│  └── 动作：告警通知，人工介入                                            │
└──────────────────────────────────────────────────────────────────────────┘
```

### 11.6 成本对比

```
┌──────────────────────────────────────────────────────────────────────────┐
│  假设：100TB 商品索引，日常 5K QPS，大促峰值 100K QPS，年 6 次大促         │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  方案 A: 传统固定集群（按峰值预留）                                       │
│  ├── 节点数：50 台（16C64G + 2TB SSD）                                   │
│  ├── 年成本：50 × $2000/月 = $1,200,000/年                              │
│  └── 日常利用率：~10%                                                    │
│                                                                          │
│  方案 B: Remote Store + 弹性搜索节点                                      │
│  ├── 常驻写入节点：5 台 × $2000/月 = $120,000/年                         │
│  ├── 常驻搜索节点：5 台 × $1500/月 = $90,000/年                          │
│  ├── 大促弹性节点：45 台 × 6次 × 8h × $3/h = $6,480/年                  │
│  ├── 远程存储：100TB × $23/TB/月 = $27,600/年                            │
│  ├── 远程请求费用：~$10,000/年                                            │
│  ├── 年总成本：~$254,080/年                                              │
│  └── 日常利用率：~60%                                                    │
│                                                                          │
│  成本节省：约 79%                                                         │
│                                                                          │
│  注：以上为估算，实际取决于云厂商定价和具体配置                            │
└──────────────────────────────────────────────────────────────────────────┘
```

### 11.7 实施前提与风险

```
必要前提：
├── 远程存储基础设施就绪（S3/OSS/MinIO，带宽充足）
├── ES/OpenSearch 版本支持 Remote Store（OpenSearch 2.x+ / ES Frozen Tier 7.12+）
├── K8s 集群有足够的预留资源或能快速从云获取资源
├── 监控体系完善（能感知缓存命中率、远程延迟等指标）
└── 已在非核心环境验证过全流程（扩容、缩容、故障恢复）

主要风险：
├── 远程存储故障 → 搜索节点无法加载新数据（缓存可临时撑住）
├── 预热不充分 → 大促开始时 P99 spike（需要提前预热时间）
├── 网络带宽瓶颈 → 大量节点同时从远程拉取数据
├── 新机制的未知bug → 需要灰度验证
└── 运维团队对新架构不熟悉 → 故障响应时间可能变长
```

---

## 12. 高频更新 + 高实时性场景的架构选型与实现方案

### 12.1 问题定义

#### 12.1.1 场景特征

```
典型业务场景：
├── 电商商品索引 — 库存/价格/状态 每秒数万次更新，搜索要求 1s 内可见
├── 实时风控 — 用户画像/风险标签 毫秒级更新，查询要求准实时
├── 物流轨迹 — 包裹状态持续更新，用户查询要看到最新
├── 社交 Feed — 点赞/评论数高频更新，读多写多
└── 金融行情 — 持仓/余额实时更新，T+0 查询
```

量化指标：
| 指标 | 典型值 |
|------|--------|
| 更新频率 | 1K-100K docs/s per index |
| 搜索实时性 | 更新后 1-5s 内搜索可见 |
| 文档生命周期 | 非时序，长期存在且反复更新 |
| 单文档更新次数 | 数十到数百次/天 |
| 读写比 | 1:1 到 10:1 |

#### 12.1.2 Remote Store Write-Through 面临的矛盾

```
┌─────────────────────────────────────────────────────────────────┐
│                    矛盾的根源                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Remote Store Write-Through 设计假设：                            │
│  ─────────────────────────────────────                           │
│  1. refresh 间隔相对较长（5s-30s）                                │
│  2. 每次 refresh 产生的 Segment 大小适中                          │
│  3. Segment 上传远程的延迟可以接受                                │
│  4. 写入吞吐相对可预测                                           │
│                                                                  │
│  高频更新场景的现实：                                             │
│  ─────────────────                                               │
│  1. 要求 refresh_interval = 1s 才能满足实时性                     │
│  2. 1s 内只攒了几十 KB 的小 Segment                               │
│  3. 每秒一次远程上传 → 远程存储 IOPS 爆炸                         │
│  4. 更新 = delete old + index new → tombstone 加速 merge           │
│  5. merge 产生大 Segment 重新上传 → 带宽峰值不可预测               │
│                                                                  │
│  结果：                                                           │
│  ─────                                                           │
│  ├── 远程存储小文件过多 → LIST/GET 操作延迟升高                    │
│  ├── 带宽不稳定 → Replica 拉取延迟抖动 → 搜索实时性反而变差        │
│  ├── refresh 被上传阻塞 → 本地搜索实时性也受损                     │
│  └── merge 上传 + 正常 refresh 上传并发 → 带宽争抢                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 12.1.3 量化分析

```
假设：10 万 doc/s 更新，refresh_interval=1s

每次 refresh：
├── 新增 Segment: ~100K docs × 1KB avg = ~100MB 文档数据
├── 但 ES 内部编码后实际 Segment 大小: ~30-80MB（含倒排/列存/stored）
├── 同时产生等量 delete tombstone（.liv 文件更新）
└── 每秒上传量: 30-80 MB/s 仅用于 refresh upload

merge 产生额外带宽：
├── merge 比率约 1:10（10个 100MB Segment merge 为 1GB）
├── merge 后上传: 峰值额外 200-500 MB/s
└── merge 期间 CPU/IO 与正常写入争抢

远程存储压力：
├── 每秒 PUT 请求: 5-20 个文件（Segment 各组成文件）
├── 每分钟累计: 300-1200 个小对象
├── 每小时累计: 18K-72K 个对象（GC 清理压力巨大）
└── S3 LIST 操作成本: $0.005/1000 requests × 72K/h = $0.36/h per shard
```

### 12.2 架构选型决策树

```
┌─────────────────────────────────────────────────────────────────┐
│                    架构选型决策树                                  │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                   需要弹性伸缩？
                   ┌────┴────┐
                  否          是
                   │          │
            传统架构         是否能容忍
           (方案 A)        搜索延迟 3-5s？
                          ┌───┴────┐
                         是         否
                          │          │
                  Remote Store     更新频率
                  标准模式        >10K/s？
                  (方案 B)     ┌───┴────┐
                              否         是
                               │          │
                        混合时间窗口    写入/搜索
                          架构         节点分离
                        (方案 C)      (方案 D)
                                        │
                                   是否需要
                                  亚秒级可见？
                                  ┌───┴────┐
                                 否         是
                                  │          │
                            异步上传+      纯本地写入+
                            延迟容忍       同步复制+
                            (方案 D)      定时远程归档
                                         (方案 E)
```

### 12.3 方案 A：传统架构优化（不引入远程存储）

#### 适用场景
- 不需要弹性伸缩能力
- 单集群规模可控（< 50 节点）
- 运维团队对传统架构经验丰富

#### 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                   传统架构 + 写入优化                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ Data-1   │    │ Data-2   │    │ Data-3   │              │
│  │ (Hot)    │    │ (Hot)    │    │ (Hot)    │              │
│  │ P0,R1    │    │ P1,R2    │    │ P2,R0    │              │
│  │ NVMe SSD │    │ NVMe SSD │    │ NVMe SSD │              │
│  └──────────┘    └──────────┘    └──────────┘              │
│       │                │                │                    │
│       └────────────────┴────────────────┘                    │
│                        │                                     │
│              Document Replication                             │
│           (Primary → Replica 同步)                           │
│                                                              │
│  优化手段：                                                   │
│  ├── refresh_interval = 1s                                   │
│  ├── index.translog.durability = async（200ms flush）         │
│  ├── index.merge.policy.floor_segment = 50mb                 │
│  ├── index.merge.scheduler.max_thread_count = 2              │
│  └── 业务层: 合并更新（batch update 500ms 窗口）              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### 关键优化点

```java
// 1. 业务层 — 更新合并（减少实际写入 ES 的频率）
public class UpdateBatcher {
    private final Map<String, Document> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    
    // 500ms 内对同一文档的多次更新合并为一次
    public void submit(String docId, Document update) {
        pending.merge(docId, update, Document::merge);
    }
    
    // 定时刷入 ES（500ms 窗口）
    @Scheduled(fixedDelay = 500)
    public void flush() {
        if (pending.isEmpty()) return;
        Map<String, Document> batch = new HashMap<>(pending);
        pending.clear();
        bulkIndex(batch); // 一次 bulk 请求
    }
}
```

```
性能预估：
├── 10 万次/s 文档更新，合并后实际写入 ES: ~2-5 万次/s
├── refresh_interval=1s → 搜索延迟 1-2s
├── 单节点写入上限: ~2 万 doc/s（取决于文档大小/mapping 复杂度）
├── 需要 3-5 个数据节点承载写入
└── 无弹性伸缩能力，扩容需 reindex 或增加分片
```

#### 局限性
- 扩容需要分片迁移，耗时分钟到小时级
- Replica 需要 Document Replay（重放索引操作），Primary 压力大
- 无法独立扩展搜索能力

---

### 12.4 方案 B：Remote Store 标准模式 + 参数调优

#### 适用场景
- 需要弹性伸缩
- 可以容忍 3-5s 搜索延迟
- 更新频率中等（< 5 万/s）

#### 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│            Remote Store + 调优后的 Write-Through                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────┐                     │
│  │         Write Nodes (Primary)           │                     │
│  │  ┌─────────┐  ┌─────────┐             │                     │
│  │  │ Write-1 │  │ Write-2 │             │                     │
│  │  │ NVMe    │  │ NVMe    │             │                     │
│  │  └────┬────┘  └────┬────┘             │                     │
│  └───────┼─────────────┼─────────────────┘                     │
│          │             │                                         │
│          ▼             ▼                                         │
│  ┌────────────────────────────────────────┐                     │
│  │       Remote Store (S3/OSS/HDFS)        │                     │
│  │  ┌─────────────────────────────────┐   │                     │
│  │  │ refresh_interval = 5s            │   │  ← 关键调优        │
│  │  │ 每 5s 上传一批 Segment           │   │                     │
│  │  │ Segment 更大 → 远程小文件更少     │   │                     │
│  │  └─────────────────────────────────┘   │                     │
│  └───────────────────┬────────────────────┘                     │
│                      │                                           │
│          Segment Replication (pull)                               │
│                      │                                           │
│  ┌───────────────────▼────────────────────┐                     │
│  │        Search Nodes (Replica)           │                     │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ │                     │
│  │  │Search-1 │ │Search-2 │ │Search-3 │ │ ← 弹性扩缩          │
│  │  │FileCache│ │FileCache│ │FileCache│ │                     │
│  │  └─────────┘ └─────────┘ └─────────┘ │                     │
│  └────────────────────────────────────────┘                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 关键配置

```yaml
# Index Settings
index.refresh_interval: "5s"              # 从 1s 放宽到 5s
index.remote_store.enabled: true
index.replication.type: "SEGMENT"         # Segment Replication 代替 Document Replication
index.translog.durability: "async"
index.translog.sync_interval: "5s"
index.merge.policy.floor_segment: "100mb" # 提高 floor 减少小 Segment 数量
index.merge.policy.max_merged_segment: "5gb"

# Cluster Settings
cluster.remote_store.segment.transfer.timeout: "30s"
cluster.remote_store.translog.transfer.timeout: "30s"

# 远程上传并发控制
cluster.remote_store.segment.pressure.enabled: true
cluster.remote_store.segment.pressure.limit: 10  # 单节点最大并发上传
```

#### 性能对比

```
                        refresh=1s          refresh=5s
远程上传频率:            1 次/s/shard        0.2 次/s/shard
单次 Segment 大小:       30-80MB             150-400MB
远程小文件数/小时:        18K-72K/shard       3.6K-14.4K/shard
搜索可见延迟:            1-2s                5-7s（含上传+拉取）
远程存储 PUT 成本:       $2.6/天/shard       $0.5/天/shard
网络带宽峰值:            80MB/s              400MB/s（burst）
                        (持续高)             (间歇性)
```

#### 局限性
- 5s+ 搜索延迟对部分业务不可接受
- 单次上传大 Segment (400MB) 时网络突发带宽要求高
- merge 与 refresh upload 仍然存在带宽争抢

---

### 12.5 方案 C：混合时间窗口架构

#### 适用场景
- 需要弹性伸缩
- 实时性要求高（1-2s 可见）
- 更新频率中高（5-50 万/s）
- 数据有明显的"热/温"访问模式

#### 核心思想

**近期数据（热窗口）保持纯本地 + Document Replication，超过时间窗口后切换为 Remote Store。**

```
┌─────────────────────────────────────────────────────────────────────┐
│                     混合时间窗口架构                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  时间轴:  [现在] ◄──── 2h ────► [切换点] ◄──── ∞ ────►               │
│                                                                      │
│  ┌───────────────────────┐     ┌───────────────────────────┐        │
│  │    Hot Window (0-2h)   │     │    Remote Window (2h+)    │        │
│  ├───────────────────────┤     ├───────────────────────────┤        │
│  │ • refresh_interval=1s │     │ • 数据已上传 Remote Store  │        │
│  │ • Document Replication │     │ • Segment Replication     │        │
│  │ • 纯本地 NVMe          │     │ • 搜索节点从远程加载       │        │
│  │ • 无远程上传延迟        │     │ • 支持弹性扩缩搜索节点    │        │
│  │ • 写入+搜索同节点      │     │ • 数据只读               │        │
│  └───────────────────────┘     └───────────────────────────┘        │
│           │                              ▲                           │
│           │     Rollover + 属性切换       │                           │
│           └──────────────────────────────┘                           │
│                                                                      │
│  实现方式：Rollover by time + Index Template                          │
│  当前写入索引: product-live-2024.01.15.14                             │
│  2 小时前索引: product-live-2024.01.15.12 → 切换为 Remote Store       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 实现细节

```yaml
# Index Template — 写入期（Hot Window）
PUT _index_template/product-live-hot
{
  "index_patterns": ["product-live-*"],
  "template": {
    "settings": {
      "index.refresh_interval": "1s",
      "index.number_of_replicas": 1,
      "index.replication.type": "DOCUMENT",   # Document Replication（实时）
      "index.remote_store.enabled": false,     # 热窗口不上传远程
      "index.routing.allocation.require.tier": "hot",
      "index.lifecycle.rollover_alias": "product-live"
    }
  }
}

# ILM Policy — 2h 后切换
PUT _ilm/policy/product-live-policy
{
  "phases": {
    "hot": {
      "actions": {
        "rollover": {
          "max_age": "2h",
          "max_primary_shard_size": "50gb"
        }
      }
    },
    "warm": {
      "min_age": "0m",
      "actions": {
        "migrate": { "enabled": true },
        "allocate": { "require": { "tier": "warm" } },
        # 切换为 Remote Store 模式
        "custom_remote_store_enable": {
          "replication_type": "SEGMENT",
          "remote_store_enabled": true
        }
      }
    }
  }
}
```

#### 难点：跨索引搜索的透明性

```
用户搜索 "product-live" alias
    │
    ├── product-live-2024.01.15.14 (当前热索引，本地 Document Replication)
    ├── product-live-2024.01.15.12 (2h前，Remote Store + Segment Replication)
    ├── product-live-2024.01.15.10 (4h前，Remote Store)
    └── ...
    
对用户完全透明：Alias 自动路由，搜索结果自动合并
```

#### 难点：Rollover 瞬间的更新处理

```
┌─────────────────────────────────────────────────────────────────┐
│  问题：商品文档长期存在，更新可能落在已 rollover 的索引上            │
│                                                                  │
│  解决方案：Update-by-Query 路由 + 双写                            │
│                                                                  │
│  方案 1: 业务层路由                                               │
│  ────────────────                                                │
│  写入层维护 docId → indexName 映射（Redis）                       │
│  更新时查映射找到目标索引 → 直接 update                            │
│  缺点：映射维护成本高                                             │
│                                                                  │
│  方案 2: Delete + Re-Index（推荐）                                │
│  ────────────────────────────                                    │
│  更新 = 在旧索引 delete old doc + 在当前热索引 index new doc       │
│  搜索通过 alias 覆盖所有索引，新文档自然覆盖                       │
│  配合 _routing 确保查询效率                                       │
│                                                                  │
│  方案 3: 实体索引不 rollover，仅标记远程归档                       │
│  ──────────────────────────────────────────                       │
│  对非时序实体索引，不做 rollover，而是在 Segment 级别               │
│  标记"冷 Segment"上传远程（参见 Chapter 9 TieredDirectory）        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 性能与成本

```
对比项               纯 Remote Store    混合窗口架构
搜索实时性(热数据)    5-7s               1-2s
搜索实时性(温数据)    5-7s               5-7s（可接受，非活跃数据）
写入吞吐             受远程上传制约       纯本地，无制约
弹性能力             全量弹性            温数据部分弹性
存储成本             最低（全远程）       中等（热窗口本地副本 + 远程）
架构复杂度           低                  中高
适合的更新频率       < 5万/s             5-50万/s
```

---

### 12.6 方案 D：写入/搜索完全分离 + 异步远程归档

#### 适用场景
- 最高实时性要求（亚秒 ~ 1s）
- 需要弹性搜索能力
- 更新频率极高（> 50 万/s）
- 可以接受"弹性搜索节点的数据有 30s-2min 延迟"

#### 核心思想

**写入节点 100% 本地化保证写入性能和实时性，搜索分为"实时搜索层"和"弹性搜索层"。**

```
┌─────────────────────────────────────────────────────────────────────────┐
│              写入/搜索完全分离 + 异步远程归档                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────┐                   │
│  │              Write Layer (固定)                    │                   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐          │                   │
│  │  │Write-1  │  │Write-2  │  │Write-3  │          │                   │
│  │  │Primary  │  │Primary  │  │Primary  │          │                   │
│  │  │NVMe SSD │  │NVMe SSD │  │NVMe SSD │          │                   │
│  │  │         │  │         │  │         │          │                   │
│  │  │ 1s刷新  │  │ 1s刷新  │  │ 1s刷新  │          │                   │
│  │  └────┬────┘  └────┬────┘  └────┬────┘          │                   │
│  └───────┼─────────────┼─────────────┼──────────────┘                   │
│          │             │             │                                    │
│          │  Doc Replication (同步, 1 Replica)                             │
│          ▼             ▼             ▼                                    │
│  ┌──────────────────────────────────────────────────┐                   │
│  │         Realtime Search Layer (固定)               │                   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐          │                   │
│  │  │Search-1 │  │Search-2 │  │Search-3 │          │                   │
│  │  │Replica  │  │Replica  │  │Replica  │          │                   │
│  │  │NVMe SSD │  │NVMe SSD │  │NVMe SSD │          │                   │
│  │  │ 1s 可见 │  │ 1s 可见 │  │ 1s 可见 │          │                   │
│  │  └─────────┘  └─────────┘  └─────────┘          │                   │
│  └──────────────────────────────────────────────────┘                   │
│          │                                                               │
│          │  Async Upload (每 30s-2min 批量上传)                           │
│          ▼                                                               │
│  ┌──────────────────────────────────────────────────┐                   │
│  │            Remote Store (S3/OSS)                   │                   │
│  └───────────────────────┬──────────────────────────┘                   │
│                          │  Segment Replication (pull)                    │
│                          ▼                                               │
│  ┌──────────────────────────────────────────────────┐                   │
│  │      Elastic Search Layer (弹性扩缩)              │                   │
│  │  ┌─────────┐ ┌─────────┐     ┌─────────┐        │                   │
│  │  │Elastic-1│ │Elastic-2│ ... │Elastic-N│        │                   │
│  │  │FileCache│ │FileCache│     │FileCache│        │                   │
│  │  │ 30s-2m  │ │ 30s-2m  │     │ 30s-2m  │        │                   │
│  │  │ 延迟    │ │ 延迟    │     │ 延迟    │        │                   │
│  │  └─────────┘ └─────────┘     └─────────┘        │                   │
│  └──────────────────────────────────────────────────┘                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 搜索路由策略

```
┌─────────────────────────────────────────────────────────────────┐
│                     搜索路由决策                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Coordinating Node 收到搜索请求                                   │
│       │                                                          │
│       ├── 请求标记 realtime=true 或 来自核心业务                   │
│       │       → 路由到 Realtime Search Layer                      │
│       │       → 保证 1s 实时性                                    │
│       │       → 但搜索能力有限（固定 3 节点）                      │
│       │                                                          │
│       ├── 请求来自大促/高并发入口                                  │
│       │       → 路由到 Elastic Search Layer                       │
│       │       → 数据延迟 30s-2min 但并发能力强                    │
│       │       → 可弹性扩到 50+ 节点                               │
│       │                                                          │
│       └── 混合路由（推荐）                                        │
│               → Realtime 层处理 top-K 排序/聚合                   │
│               → Elastic 层处理长尾/全量扫描查询                    │
│               → Coordinating 层合并结果                            │
│                                                                  │
│  实现：通过 Search Pipeline / Custom Routing Plugin               │
│                                                                  │
│  // 路由逻辑伪代码                                                │
│  if (request.preference == "_realtime") {                         │
│      route to realtime_nodes;                                    │
│  } else if (cluster.elastic_nodes.qps > threshold) {             │
│      route to elastic_nodes;                                     │
│  } else {                                                        │
│      route by adaptive_replica_selection;                         │
│  }                                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 异步远程上传策略

```java
/**
 * 不是每次 refresh 都上传，而是攒 batch 后批量上传
 * 减少远程存储 IOPS，增大单次上传的 Segment 大小
 */
public class BatchedRemoteUploader implements RefreshListener {
    
    private final long uploadIntervalMs = 30_000; // 30s 上传一次
    private final long maxPendingBytes = 512 * 1024 * 1024; // 512MB 触发上传
    private final AtomicLong pendingBytes = new AtomicLong(0);
    private volatile long lastUploadTime = System.currentTimeMillis();
    
    @Override
    public void afterRefresh(boolean didRefresh) {
        if (!didRefresh) return;
        
        long pending = pendingBytes.addAndGet(getNewSegmentBytes());
        long elapsed = System.currentTimeMillis() - lastUploadTime;
        
        // 触发上传条件：时间到 或 积累量达到阈值
        if (elapsed >= uploadIntervalMs || pending >= maxPendingBytes) {
            triggerBatchUpload();
        }
    }
    
    private void triggerBatchUpload() {
        // 等待当前正在进行的 merge 完成
        // 上传 merge 后的大 Segment 而非 merge 前的小 Segment
        // 这样远程存储收到的都是较大的文件
        List<SegmentInfo> readySegments = getSegmentsNotInMerge();
        uploadBatch(readySegments);
        
        lastUploadTime = System.currentTimeMillis();
        pendingBytes.set(0);
    }
}
```

#### 性能分析

```
                      Realtime Layer       Elastic Layer
数据延迟:              1s                   30s - 2min
搜索并发能力:          固定（如 1000 QPS）   弹性（可扩到 50000+ QPS）
节点数:               固定 3-6 台           弹性 0-50 台
存储:                 本地 NVMe             远程 + FileCache
成本:                 固定                  按需
故障影响:             单点故障影响搜索       节点无状态，故障无影响

远程上传压力对比:
├── 标准 Remote Store (1s refresh): 每分钟 60 次上传/shard
├── 本方案 (30s batch):             每分钟 2 次上传/shard
├── 上传文件大小: 小 Segment 已 merge → 上传的是 200MB-1GB 的大文件
└── 远程对象数量/小时: 减少 30x
```

---

### 12.7 方案 E：纯本地实时 + 定时快照归档（最保守方案）

#### 适用场景
- 实时性要求极端（< 500ms）
- 弹性需求仅限于灾备/读扩展，非实时链路
- 数据量可控（单索引 < 500GB）

#### 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│            纯本地实时 + 定时快照归档                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────┐                    │
│  │       Primary Cluster (实时读写)         │                    │
│  │  • 传统架构，Document Replication        │                    │
│  │  • refresh_interval = 1s                 │                    │
│  │  • 全量 NVMe SSD                         │                    │
│  │  • 承担所有实时搜索                       │                    │
│  └──────────────────┬──────────────────────┘                    │
│                     │                                            │
│            Snapshot (每 15min / 每小时)                           │
│                     │                                            │
│                     ▼                                            │
│  ┌─────────────────────────────────────────┐                    │
│  │       Remote Store (S3/OSS)              │                    │
│  │  • Searchable Snapshot 格式              │                    │
│  │  • 用于灾备恢复 + 离线分析               │                    │
│  └──────────────────┬──────────────────────┘                    │
│                     │                                            │
│            按需挂载 (Frozen/Partial Mount)                        │
│                     │                                            │
│                     ▼                                            │
│  ┌─────────────────────────────────────────┐                    │
│  │    Analytics Cluster (离线分析/灾备)      │                    │
│  │  • Searchable Snapshot mount             │                    │
│  │  • 用于报表/数据分析/历史查询            │                    │
│  │  • 弹性扩缩（不影响实时链路）            │                    │
│  └─────────────────────────────────────────┘                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 适用性评估

```
优势：
├── 写入和搜索零额外延迟（纯本地）
├── 架构简单，运维成熟
├── 实时链路完全不受远程存储影响
└── 通过 Snapshot 获得灾备能力

劣势：
├── 主集群无弹性能力（扩容 = 分片迁移）
├── Snapshot 只是"某时刻的快照"，非连续
├── 离线集群数据延迟 15min-1h
└── 存储成本高（主集群全量本地存储）
```

---

### 12.8 方案对比总结

```
┌────────┬──────────┬──────────┬───────────┬──────────┬──────────┐
│        │ 方案 A   │ 方案 B   │ 方案 C    │ 方案 D   │ 方案 E   │
│        │传统优化  │Remote标准│混合窗口   │写搜分离  │本地+快照 │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│搜索延迟│ 1s       │ 5-7s     │ 1s(热)    │ 1s(实时) │ <500ms   │
│        │          │          │ 5s(温)    │ 30s(弹性)│          │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│写入上限│ 5万/s    │ 3万/s    │ 5万/s     │ 10万+/s  │ 5万/s    │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│弹性能力│ 无       │ 全量弹性 │ 温数据弹性│ 搜索层   │ 离线弹性 │
│        │          │          │           │ 全量弹性 │          │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│架构复杂│ 低       │ 低       │ 中高      │ 高       │ 低       │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│存储成本│ 高       │ 低       │ 中        │ 中       │ 高       │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│适合更新│ <5万/s   │ <5万/s   │ 5-50万/s  │ >50万/s  │ <5万/s   │
│频率    │          │          │           │          │          │
├────────┼──────────┼──────────┼───────────┼──────────┼──────────┤
│适合业务│ 中小集群 │ 日志/时序│ 电商商品  │ 金融/    │ 核心交易 │
│        │ 资源充足 │ 可容忍   │ 物流轨迹  │ 大型电商 │ 极低延迟 │
│        │          │ 延迟     │           │          │          │
└────────┴──────────┴──────────┴───────────┴──────────┴──────────┘
```

### 12.9 关键实现难点与解决方案

#### 12.9.1 Merge 与远程上传的带宽争抢

```
问题：
Lucene merge 产生大 Segment（GB 级） → 上传到远程 → 占满网络带宽
同时正常 refresh 也在上传新 Segment → 排队等待 → 搜索延迟升高

解决方案：带宽配额 + 优先级调度

┌─────────────────────────────────────────────────────────────┐
│              Remote Upload Bandwidth Scheduler               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  总带宽池: 1 Gbps (可配置)                                    │
│                                                              │
│  ┌─────────────────────────────────────────────┐            │
│  │ Priority 1: Refresh Upload (保障型)          │            │
│  │ 保证带宽: 400 Mbps                           │            │
│  │ 用途: 新 Segment 上传，影响搜索实时性         │            │
│  └─────────────────────────────────────────────┘            │
│                                                              │
│  ┌─────────────────────────────────────────────┐            │
│  │ Priority 2: Merge Upload (弹性型)            │            │
│  │ 可用带宽: 总带宽 - P1 实际使用               │            │
│  │ 用途: Merge 后大 Segment 上传                │            │
│  │ 策略: 非高峰时段上传 / 可延迟 / 可限速       │            │
│  └─────────────────────────────────────────────┘            │
│                                                              │
│  ┌─────────────────────────────────────────────┐            │
│  │ Priority 3: Replica Fetch (次优先)           │            │
│  │ 可用带宽: 单独配额 或 共享剩余               │            │
│  │ 用途: 搜索节点从远程拉取 Segment             │            │
│  └─────────────────────────────────────────────┘            │
│                                                              │
│  调度算法: Weighted Fair Queuing                              │
│  监控指标: upload_queue_depth, upload_latency_p99            │
│  背压机制: 当 queue_depth > 100 时暂停 merge trigger          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### 12.9.2 Delete Tombstone 膨胀问题

```
问题：
├── 高频更新 = delete + re-index
├── 每次更新产生 .liv 文件中的 tombstone 位标记
├── merge 前：大量 Segment 包含已删除文档 → 浪费存储和 IO
├── merge 后：上传新 Segment 到远程 + 删除旧 Segment 对象
└── 远程存储的"删除"实际是标记删除，GC 有延迟

解决方案：
┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  1. Force Merge 调度优化                                      │
│  ─────────────────────                                       │
│  • 监控 deleted_docs_percentage                              │
│  • 当 > 30% 时触发 merge（而非等待自动 merge policy）         │
│  • merge 在低峰时段执行（如凌晨）                             │
│  • merge 后统一上传，避免白天带宽争抢                          │
│                                                              │
│  2. 上传策略：只上传 merge 后的 Segment                       │
│  ─────────────────────────────────────                       │
│  • 小 Segment（< 100MB）不上传，等 merge 后上传               │
│  • 远程存储只保留"稳定"的大 Segment                           │
│  • 减少远程对象数量 10-50x                                    │
│  • 代价：Elastic Layer 延迟从 30s 增大到 merge 间隔            │
│                                                              │
│  3. 远程 GC 策略                                              │
│  ───────────                                                 │
│  • 维护 remote_segment_metadata 索引跟踪有效 Segment          │
│  • 定时清理已被 merge 替代的旧 Segment 对象                   │
│  • S3 Lifecycle Rule: 过期对象 7 天后自动删除                  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### 12.9.3 Realtime GET 与搜索可见性的区别

```
┌─────────────────────────────────────────────────────────────┐
│  对"实时性"要求的细化 — 不同 API 的实时语义不同                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  GET /index/_doc/{id}?realtime=true                          │
│  ─────────────────────────────────                           │
│  • 直接从 Translog 读取，不依赖 refresh                       │
│  • 延迟: < 5ms                                               │
│  • 用途: 单文档精确查询（订单详情、商品详情）                  │
│  • Remote Store 对此无影响（Translog 在本地）                  │
│                                                              │
│  POST /index/_search                                         │
│  ───────────────────                                         │
│  • 依赖 Lucene Segment 可见 → 必须等 refresh                  │
│  • 延迟: refresh_interval (1s-5s)                             │
│  • 用途: 全文搜索、聚合、过滤查询                             │
│  • Remote Store 额外增加上传+拉取延迟                         │
│                                                              │
│  POST /index/_search (near-realtime with refresh)            │
│  ────────────────────────────────────────────                │
│  • 在搜索前强制 refresh: ?refresh=true 或 refresh=wait_for    │
│  • 延迟: refresh 耗时 (10-100ms)                              │
│  • 代价: 高频使用导致大量小 Segment，严重影响性能              │
│  • 建议: 仅用于关键路径的少量查询                             │
│                                                              │
│  结论：                                                       │
│  ─────                                                       │
│  很多"高实时性"需求实际上是单文档查询                          │
│  → Realtime GET 天然满足，无需为了搜索可见性牺牲架构           │
│  需要区分：                                                   │
│  ├── "我要立刻看到我刚改的商品" → Realtime GET，无影响         │
│  └── "全站搜索1s内出现新商品" → 搜索可见性，需要架构设计       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### 12.9.4 高频更新下 Translog 的处理

```
问题：
├── Remote Store 模式下 Translog 也上传远程（用于持久化）
├── 高频更新 → Translog 体积增长极快
├── 每 sync_interval 上传一次 → 频繁远程写入
└── Translog 远程上传延迟 → 影响 index 请求的 ack

解决方案：

1. Translog durability = async（推荐）
   • 不等待 Translog fsync 到远程就返回 ack
   • 风险：节点宕机丢失最近 sync_interval（如 5s）的数据
   • 对于可重放的业务数据（如 MQ 消费），此风险可接受

2. Translog 本地持久化 + 定期远程同步
   • Translog 写本地磁盘（fsync）保证不丢
   • 每 30s 将 Translog 批量上传远程
   • 结合：Primary 宕机时从 Translog 远程副本恢复

3. 独立 Translog 上传通道
   • Translog 上传与 Segment 上传使用独立带宽配额
   • 避免两者争抢导致写入超时

配置示例：
index.translog.durability: "async"
index.translog.sync_interval: "5s"
index.translog.flush_threshold_size: "1gb"  # 增大 flush 阈值
index.remote_store.translog.buffer_interval: "30s"
```

### 12.10 推荐选型指南

```
┌─────────────────────────────────────────────────────────────────┐
│                    选型决策 Quick Reference                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  你的业务是：                                                     │
│                                                                  │
│  "库存/价格每秒更新数万次，用户搜索要 1s 内看到"                   │
│  → 方案 D（写搜分离 + 异步归档）                                  │
│  → 实时搜索层保证 1s，弹性搜索层延迟 30s 承担洪峰                 │
│                                                                  │
│  "商品信息批量更新（如促销改价），搜索 3-5s 可见即可"              │
│  → 方案 B（Remote Store 标准 + refresh_interval=5s）              │
│  → 最简架构，全量弹性                                             │
│                                                                  │
│  "订单/物流状态更新频繁，但用户主要查自己的单"                     │
│  → 方案 A + Realtime GET                                         │
│  → 单文档查询走 Translog，无需搜索可见性                          │
│                                                                  │
│  "大型电商搜索，千万商品实时更新 + 大促弹性"                       │
│  → 方案 C（混合窗口）或 方案 D（写搜分离）                        │
│  → 看能否接受温数据 5s 延迟来决定                                 │
│                                                                  │
│  "金融/风控，亚秒级实时，集群规模可控，不需弹性"                   │
│  → 方案 E（纯本地 + 快照归档）                                    │
│  → 在线链路不碰远程存储                                           │
│                                                                  │
│  关键原则：                                                       │
│  ─────────                                                       │
│  1. 先区分"Realtime GET"和"搜索可见性"需求                        │
│  2. 弹性和实时性本质上是 tradeoff                                  │
│  3. 远程上传频率 = 远程存储成本 = 搜索延迟增量                     │
│  4. 业务层合并更新是所有方案的共同前提                             │
│  5. 混合架构 > 一刀切：不同延迟容忍度走不同链路                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 12.11 方案 F：Hybrid Segment Replication — 小 Segment P2P 直复制 + 大 Segment 远程存储

#### 12.11.1 核心思想

```
┌─────────────────────────────────────────────────────────────────────────┐
│                   Hybrid Segment Replication                              │
│          "小文件走近路，大文件走远程" — 兼顾实时性与弹性                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  观察：                                                                   │
│  ─────                                                                   │
│  • refresh 产生的新 Segment 通常很小（几 MB ~ 几十 MB）                    │
│  • merge 后的 Segment 很大（几百 MB ~ 数 GB）                             │
│  • 小 Segment 生命周期短（很快被 merge 消灭）                              │
│  • 大 Segment 生命周期长（稳定存在，值得持久化到远程）                      │
│                                                                          │
│  设计原则：                                                               │
│  ─────────                                                               │
│  小 Segment（< threshold）:                                               │
│    → Primary 直接 P2P 复制给固定 Replica（同机房，低延迟）                 │
│    → 不上传远程存储                                                       │
│    → 保证搜索实时性（1s 可见）                                            │
│    → 生命周期短，很快被 merge 替代，远程无需保留                            │
│                                                                          │
│  大 Segment（≥ threshold，通常是 merge 产物）:                             │
│    → 上传远程存储                                                         │
│    → Elastic 搜索节点从远程加载                                            │
│    → 本地 Replica 也切换为远程引用（释放本地磁盘）                          │
│    → 长期稳定，值得持久化                                                  │
│                                                                          │
│  效果：                                                                   │
│  ─────                                                                   │
│  • 实时性 = Document Replication 级别（P2P 延迟 < 10ms）                   │
│  • 弹性 = Remote Store 级别（大 Segment 在远程，新节点可加载）              │
│  • 远程上传量 = 仅 merge 后的大 Segment（减少 90%+ 的上传次数）            │
│  • 远程小文件问题彻底消失                                                  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.2 架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│  ┌─────────────────────────────────────┐                                    │
│  │         Primary Shard (Write Node)   │                                    │
│  │                                      │                                    │
│  │  refresh (1s)                        │                                    │
│  │     │                                │                                    │
│  │     ▼                                │                                    │
│  │  ┌────────────┐                      │                                    │
│  │  │ New Segment │ (5MB)               │                                    │
│  │  │ < threshold │─────────────────────┼───── P2P 直接复制 ────────┐        │
│  │  └────────────┘                      │                           │        │
│  │     │                                │                           │        │
│  │     │ merge                          │                           │        │
│  │     ▼                                │                           │        │
│  │  ┌────────────┐                      │                           │        │
│  │  │Merged Seg  │ (500MB)             │                           │        │
│  │  │ ≥ threshold│─────────────────────┼──── 上传远程 ──────┐      │        │
│  │  └────────────┘                      │                    │      │        │
│  └─────────────────────────────────────┘                    │      │        │
│                                                              │      │        │
│                                                              ▼      ▼        │
│                                              ┌──────────────────────────┐   │
│                                              │   Fixed Replica Nodes     │   │
│                                              │   (Realtime Search)       │   │
│                                              │                           │   │
│  ┌────────────────────────────┐              │  小 Segment: P2P 收到即可见│   │
│  │    Remote Store (S3/OSS)    │              │  大 Segment: 切换远程引用  │   │
│  │                             │              │  搜索延迟: 1-2s           │   │
│  │  只存大 Segment:            │              └──────────────────────────┘   │
│  │  ├── merged_0.si (500MB)   │                                             │
│  │  ├── merged_1.si (800MB)   │                                             │
│  │  └── merged_2.si (1.2GB)   │                                             │
│  │                             │                                             │
│  │  无小 Segment:              │              ┌──────────────────────────┐   │
│  │  • 无 5MB 碎片             │              │   Elastic Search Nodes    │   │
│  │  • 无频繁 PUT/DELETE       │──────────────│   (弹性扩缩)              │   │
│  │  • LIST 操作极快           │              │                           │   │
│  └────────────────────────────┘              │  从远程加载大 Segment      │   │
│                                              │  搜索延迟: merge 间隔      │   │
│                                              │  (30s - 数分钟)           │   │
│                                              └──────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.3 Segment 分类阈值设计

```
┌─────────────────────────────────────────────────────────────────┐
│                  Segment 路由阈值                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  配置项: index.hybrid_replication.segment_size_threshold          │
│  默认值: 50MB                                                    │
│                                                                  │
│  阈值选择依据：                                                   │
│  ──────────────                                                  │
│  • 太小（如 5MB）:                                                │
│    → 几乎所有 Segment 都走远程 → 退化为标准 Remote Store          │
│    → 远程小文件问题重现                                           │
│                                                                  │
│  • 太大（如 500MB）:                                              │
│    → 大部分 Segment 走 P2P → Primary 网络负载高                   │
│    → 弹性搜索节点看到的数据极度滞后                               │
│    → 远程存储几乎没有数据 → 弹性扩容后几乎是空的                  │
│                                                                  │
│  • 合适值（50MB - 100MB）:                                        │
│    → 正好卡在 "refresh 产物" 和 "merge 产物" 的分界线             │
│    → Lucene TieredMergePolicy 默认 floor_segment = 2MB            │
│    → merge 通常 10 个 Segment 合并 → 产物 50-200MB                │
│    → refresh 产生的 Segment 通常 1-30MB（1s 间隔）                │
│                                                                  │
│  动态调整策略：                                                   │
│  ─────────────                                                   │
│  • 监控 P2P 复制带宽使用率                                        │
│  • 如果 > 80% 节点间带宽 → 自动提高阈值（更多走远程）            │
│  • 如果弹性层数据太老 → 自动降低阈值（更多走远程加速弹性）        │
│                                                                  │
│  特殊处理：                                                       │
│  ─────────                                                       │
│  • .liv 文件（delete tombstone）: 始终走 P2P（极小，但实时性关键）│
│  • .dvd/.dvm（doc values 更新）: 随所属 Segment 的路由            │
│  • commit point (segments_N): 始终上传远程（元数据，弹性节点需要） │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 12.11.4 实现层设计

```java
/**
 * Hybrid Replication Coordinator
 * 在 Primary Shard refresh 后，决定每个新 Segment 走哪条复制路径
 */
public class HybridReplicationCoordinator implements RefreshListener {
    
    private final long segmentSizeThreshold;  // 默认 50MB
    private final PeerReplicator peerReplicator;       // P2P 复制器
    private final RemoteStoreUploader remoteUploader;  // 远程上传器
    private final SegmentTracker segmentTracker;       // 跟踪 Segment 状态
    
    @Override
    public void afterRefresh(boolean didRefresh) {
        if (!didRefresh) return;
        
        SegmentInfos latestInfos = getLatestSegmentInfos();
        List<SegmentInfo> newSegments = segmentTracker.getNewSegments(latestInfos);
        
        for (SegmentInfo segment : newSegments) {
            long segmentSize = getSegmentSizeOnDisk(segment);
            
            if (segmentSize < segmentSizeThreshold) {
                // ─── 小 Segment: P2P 直接复制给固定 Replica ───
                peerReplicator.replicateToFixedReplicas(segment);
                segmentTracker.markAsPeerReplicated(segment);
                
            } else {
                // ─── 大 Segment: 上传远程存储 ───
                remoteUploader.uploadAsync(segment, () -> {
                    // 上传完成后通知所有 Replica
                    notifyReplicasSegmentAvailableRemote(segment);
                    segmentTracker.markAsRemoteStored(segment);
                });
            }
        }
        
        // 无论走哪条路径，都发布新的 checkpoint 给 Replica
        publishCheckpoint(latestInfos);
    }
}

/**
 * Merge 完成后的处理 — 核心衔接逻辑
 */
public class HybridMergeListener implements MergePolicy.OnMerge {
    
    @Override
    public void onMergeComplete(MergeInfo mergeInfo) {
        SegmentInfo mergedSegment = mergeInfo.getMergedSegment();
        List<SegmentInfo> sourceSegments = mergeInfo.getSourceSegments();
        
        // merge 产物通常 > threshold → 走远程上传
        if (getSegmentSize(mergedSegment) >= segmentSizeThreshold) {
            
            // 1. 上传 merge 后的大 Segment 到远程
            remoteUploader.upload(mergedSegment);
            
            // 2. 通知 Fixed Replica: 用远程引用替换之前 P2P 收到的小 Segment
            //    Replica 收到后通过 SwitchableIndexInput 切换
            notifyReplicasSwitchToRemote(mergedSegment, sourceSegments);
            
            // 3. Fixed Replica 可以删除之前 P2P 复制的小 Segment 本地文件
            //    释放本地磁盘空间
            notifyReplicasDeleteLocalSegments(sourceSegments);
            
            // 4. Elastic 搜索节点现在可以看到这些数据了
            //    （之前只存在于 Primary 和 Fixed Replica）
            publishRemoteCheckpoint(mergedSegment);
        }
    }
}
```

#### 12.11.5 Replica 端的处理流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Fixed Replica 的 Segment 生命周期                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  时间 T=0s: Primary refresh → 生成 seg_001 (10MB)                        │
│  ┌─────────────────────────────┐                                        │
│  │ Primary 发送 P2P 复制请求    │                                        │
│  │ seg_001 数据通过 Transport   │                                        │
│  │ 直接写入 Replica 本地磁盘    │                                        │
│  └──────────────┬──────────────┘                                        │
│                 │ ~5-20ms (机房内网络)                                    │
│                 ▼                                                        │
│  时间 T=0.02s: Replica 收到 seg_001，更新本地 SegmentInfos               │
│  ┌─────────────────────────────┐                                        │
│  │ IndexSearcher 刷新           │                                        │
│  │ seg_001 对搜索可见           │ ← 实时性保证！                         │
│  │ 存储: 本地磁盘               │                                        │
│  └──────────────┬──────────────┘                                        │
│                 │                                                        │
│  时间 T=30s: Primary merge → seg_001..010 合并为 seg_merged_A (500MB)    │
│  ┌─────────────────────────────┐                                        │
│  │ Primary 上传 seg_merged_A    │                                        │
│  │ 到远程存储                   │                                        │
│  └──────────────┬──────────────┘                                        │
│                 │ ~2-5s (远程上传)                                        │
│                 ▼                                                        │
│  时间 T=35s: Primary 通知 Replica "seg_merged_A 已在远程可用"             │
│  ┌─────────────────────────────────────────┐                            │
│  │ Replica 执行 Segment Switch:              │                            │
│  │                                          │                            │
│  │ 1. 打开 seg_merged_A 的远程 IndexInput   │                            │
│  │    (通过 SwitchableIndexInput)            │                            │
│  │                                          │                            │
│  │ 2. 原子替换: seg_001..010 → seg_merged_A │                            │
│  │    (SegmentInfos 更新)                    │                            │
│  │                                          │                            │
│  │ 3. 删除本地 seg_001..010 文件            │                            │
│  │    (释放 ~100MB 本地磁盘)                │                            │
│  │                                          │                            │
│  │ 4. 后续读取 seg_merged_A 走 FileCache    │                            │
│  │    (首次 miss 从远程加载，后续命中缓存)   │                            │
│  └─────────────────────────────────────────┘                            │
│                                                                          │
│  关键点:                                                                  │
│  • T=0.02s 搜索已经可见 → 实时性 ✓                                       │
│  • T=35s 本地磁盘释放 → 存储效率 ✓                                       │
│  • seg_merged_A 在远程 → 弹性节点可用 ✓                                   │
│  • 全程搜索无中断（SwitchableIndexInput 原子切换）                        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.6 Elastic 搜索节点的视角

```
┌─────────────────────────────────────────────────────────────────────────┐
│               Elastic Search Node 的数据可见时序                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Elastic 节点只从远程加载数据，所以：                                      │
│  • 无法看到"仅 P2P 复制的小 Segment"                                     │
│  • 只能看到"已上传远程的大 Segment"                                       │
│  • 数据延迟 = merge 间隔 + 上传时间（通常 30s - 数分钟）                   │
│                                                                          │
│  这是 **设计上的 tradeoff**:                                              │
│  • Fixed Replica: 实时（P2P），数量固定，成本固定                          │
│  • Elastic Replica: 延迟（远程），数量弹性，成本按需                       │
│                                                                          │
│  Elastic 节点加入流程:                                                    │
│  ─────────────────────                                                   │
│  1. 新节点启动 → 获取分配的分片列表                                       │
│  2. 从远程加载 Segment 元数据 (segments_N)                                │
│  3. 只知道远程的大 Segment → 打开 RemoteDirectory                         │
│  4. 搜索请求到达 → FileCache miss → 从远程加载到本地缓存                  │
│  5. 后续请求 → FileCache hit → 本地速度                                   │
│                                                                          │
│  数据一致性:                                                              │
│  ────────────                                                            │
│  • Elastic 节点看到的是"某个 checkpoint 时刻的全量快照"                    │
│  • 每次 Primary 上传新的大 Segment → 发布新 checkpoint                    │
│  • Elastic 节点感知到新 checkpoint → 增量加载新 Segment                    │
│  • 搜索结果最终一致（不是强一致）                                          │
│                                                                          │
│  适合场景：                                                               │
│  • 大促洪峰分流（用户对搜索结果 30s 延迟无感知）                           │
│  • 全文搜索/推荐（不要求最新数据）                                        │
│  • 长尾查询/报表查询（延迟不敏感）                                        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.7 与 OpenSearch 现有机制的映射

```
┌─────────────────────────────────────────────────────────────────┐
│          Hybrid Replication 与 OpenSearch 组件的关系              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  本方案可以基于 OpenSearch 现有组件实现：                          │
│                                                                  │
│  ┌────────────────────┬──────────────────────────────────────┐  │
│  │ 本方案概念           │ OpenSearch 已有实现                    │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ P2P 小 Segment 复制 │ Document Replication (改为文件级)     │  │
│  │                     │ 或 Segment Replication (P2P 模式)     │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ 大 Segment 远程上传  │ RemoteStoreRefreshListener           │  │
│  │                     │ + RemoteSegmentStoreDirectory          │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ Replica 本地→远程    │ SwitchableIndexInput                  │  │
│  │ 原子切换             │ + TieredDirectory                     │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ Elastic 节点加载远程 │ RemoteStoreDirectory                  │  │
│  │                     │ + FileCache                            │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ Checkpoint 发布     │ RemoteStoreRefreshListener             │  │
│  │                     │ .publishCheckpoint()                   │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ Segment 路由决策    │ 需新增: HybridReplicationCoordinator   │  │
│  ├────────────────────┼──────────────────────────────────────┤  │
│  │ Merge 后切换通知    │ 可复用: afterSyncToRemote() 回调       │  │
│  └────────────────────┴──────────────────────────────────────┘  │
│                                                                  │
│  核心新增组件：                                                   │
│  ─────────────                                                   │
│  1. HybridReplicationCoordinator (Primary 端)                    │
│     → 根据 Segment size 决定复制路径                              │
│     → 监听 refresh + merge 事件                                  │
│                                                                  │
│  2. HybridReplicaHandler (Replica 端)                            │
│     → 处理 P2P 接收 + 远程切换                                   │
│     → 管理本地小 Segment 的生命周期                               │
│     → 在 merge 通知到达后执行 switch                              │
│                                                                  │
│  3. HybridCheckpointPublisher                                    │
│     → 维护两套 checkpoint:                                       │
│       - full_checkpoint: 包含所有 Segment（给 Fixed Replica）    │
│       - remote_checkpoint: 仅远程 Segment（给 Elastic Replica）  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 12.11.8 数据流完整时序图

```
┌────────┐    ┌────────┐    ┌────────────┐    ┌────────┐    ┌────────────┐
│ Client │    │Primary │    │Fixed Replica│    │ Remote │    │Elastic Node│
└───┬────┘    └───┬────┘    └─────┬──────┘    └───┬────┘    └─────┬──────┘
    │             │               │                │               │
    │ index doc   │               │                │               │
    ├────────────►│               │                │               │
    │             │               │                │               │
    │             │─ refresh ─┐   │                │               │
    │             │            │   │                │               │
    │             │◄───────────┘   │                │               │
    │             │               │                │               │
    │             │  seg_A (8MB)  │                │               │
    │             │  < threshold  │                │               │
    │             │               │                │               │
    │             │───P2P copy────►│                │               │
    │             │   seg_A bytes │                │               │
    │             │               │                │               │
    │             │  checkpoint_1 │                │               │
    │             │──────────────►│                │               │
    │             │               │                │               │
    │             │               │─ seg_A 可搜索 ─┤               │
    │             │               │  (延迟 ~20ms) │               │
    │             │               │                │               │
    │  ... 多次 refresh，生成 seg_B, seg_C ... seg_J (均 < 50MB) ...│
    │             │               │                │               │
    │             │─── merge ──┐  │                │               │
    │             │ seg_A..J    │  │                │               │
    │             │  → seg_M    │  │                │               │
    │             │  (500MB)    │  │                │               │
    │             │◄────────────┘  │                │               │
    │             │               │                │               │
    │             │  seg_M ≥ threshold              │               │
    │             │               │                │               │
    │             │───upload seg_M─────────────────►│               │
    │             │               │                │               │
    │             │◄──────────────── upload done ───│               │
    │             │               │                │               │
    │             │  "switch to remote"             │               │
    │             │──────────────►│                │               │
    │             │               │                │               │
    │             │               │─ SwitchableInput│               │
    │             │               │  seg_A..J → remote seg_M       │
    │             │               │  delete local seg_A..J         │
    │             │               │                │               │
    │             │  remote_checkpoint_1            │               │
    │             │────────────────────────────────►│               │
    │             │               │                │               │
    │             │               │                │──load seg_M──►│
    │             │               │                │  from remote   │
    │             │               │                │               │
    │             │               │                │  seg_M 可搜索 │
    │             │               │                │  (延迟 ~35s)  │
    │             │               │                │               │
```

#### 12.11.9 性能与成本分析

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      性能对比分析                                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│              标准 Remote Store    Hybrid Replication     传统 Doc Repl    │
│  ──────────────────────────────────────────────────────────────────────  │
│  搜索延迟        5-7s               1-2s (Fixed)           1s            │
│  (Fixed Replica)                    30-120s (Elastic)                     │
│                                                                          │
│  远程上传频率     60次/min/shard     2-4次/min/shard       N/A           │
│                                     (仅 merge 产物)                      │
│                                                                          │
│  远程文件数/h     72K/shard          120-240/shard         N/A           │
│                                     (减少 300x!)                         │
│                                                                          │
│  远程文件大小     5-100MB            200MB-2GB             N/A           │
│  (平均)          (大量小文件)        (少量大文件)                         │
│                                                                          │
│  Primary 网络     低（仅上传远程）    中（P2P + 远程）      高（Doc重放） │
│  开销                                                                    │
│                                                                          │
│  Replica 磁盘     低（全远程缓存）    中（小Seg暂存        高（全量本地）│
│  使用                                + 远程缓存）                        │
│                                                                          │
│  弹性扩缩能力     全量弹性           弹性（仅远程部分）     无弹性        │
│                                                                          │
│  S3 成本/shard/天  $2.6              $0.08                 $0            │
│  (PUT+LIST)       (高频小文件)       (低频大文件)                        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                    Fixed Replica 磁盘空间分析                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  问题：P2P 复制的小 Segment 占多少本地磁盘？                               │
│                                                                          │
│  计算（假设 refresh_interval=1s, merge 间隔~30s）:                        │
│  • 每次 refresh: ~30MB 新 Segment                                        │
│  • merge 前积累: 30 个 × 30MB = ~900MB                                   │
│  • merge 后: 上传远程 → 删除本地 → 释放 900MB                            │
│                                                                          │
│  所以 Fixed Replica 本地磁盘仅需保留:                                      │
│  • "当前未 merge 的小 Segment" ≈ 最近 30s 的数据 ≈ 1GB/shard             │
│  • 远程 Segment 的 FileCache 热数据                                       │
│                                                                          │
│  vs 传统架构: 全量数据本地存储 → 可能 100GB+/shard                         │
│                                                                          │
│  结论: 磁盘节省 99%（仅保留"滑动窗口"大小的临时数据）                      │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.10 边界情况处理

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       边界情况与容错设计                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Case 1: Primary 宕机，小 Segment 还没上传远程                            │
│  ──────────────────────────────────────────────                          │
│  • Fixed Replica 持有小 Segment 的完整拷贝（P2P 已复制）                  │
│  • Replica promote 为 Primary → 数据无丢失                               │
│  • 新 Primary 继续 merge → merge 后上传远程                              │
│  • Elastic 节点: 这段时间的数据暂时不可见（等 merge + upload）             │
│  • 恢复后: 新 Primary 从自己的本地 + 远程数据恢复完整视图                  │
│                                                                          │
│  Case 2: Fixed Replica 宕机                                              │
│  ──────────────────────────                                              │
│  • Primary 检测到 Replica 下线                                           │
│  • 新 Replica 分配到其他节点                                             │
│  • Recovery: 未上传远程的小 Segment → Primary P2P 重新复制                │
│  • Recovery: 已上传远程的大 Segment → 从远程加载                          │
│  • 比传统 Peer Recovery 快得多（大部分数据从远程并行加载）                 │
│                                                                          │
│  Case 3: P2P 复制超时/网络抖动                                           │
│  ──────────────────────────────                                          │
│  • 设置 P2P 复制超时: 500ms                                              │
│  • 超时后降级: 将该 Segment 标记为"待远程上传"                            │
│  • 不阻塞 Primary 的 refresh 和后续写入                                  │
│  • Replica 在下次 checkpoint 时从远程或 Primary 补全                     │
│  • 搜索影响: 该 Replica 上这个 Segment 延迟可见（几秒，非致命）           │
│                                                                          │
│  Case 4: 远程存储上传失败                                                 │
│  ──────────────────────                                                  │
│  • 与标准 Remote Store 相同的重试策略（指数退避）                          │
│  • Primary + Fixed Replica 本地都有数据 → 不影响搜索                      │
│  • Elastic 节点: 看不到新数据直到上传恢复                                 │
│  • 背压: 本地未上传 Segment 累积 > 5GB → 减缓 merge 频率                 │
│                                                                          │
│  Case 5: Elastic 节点扩容时，部分数据只在 P2P 层                          │
│  ────────────────────────────────────────────────                        │
│  • 设计上 Elastic 节点只看远程 checkpoint                                 │
│  • 最新 30s-2min 的数据（未 merge）对 Elastic 不可见                      │
│  • 这是架构设计的 tradeoff，非 bug                                       │
│  • 如果需要 Elastic 节点也实时 → 退回方案 B（全量 Remote Store）           │
│                                                                          │
│  Case 6: Merge 产生的 Segment 刚好在阈值边界                              │
│  ─────────────────────────────────────────────                            │
│  • 阈值判断在 merge 完成后执行                                           │
│  • 使用 ≥ threshold 而非 > threshold（含等于走远程）                      │
│  • 如果 merge 产物略小于阈值 → 仍走 P2P → 等下次 merge                  │
│  • 最终所有数据都会 merge 到足够大 → 最终一致上传远程                     │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 12.11.11 配置参数汇总

```yaml
# ─── Hybrid Replication 核心配置 ───

# 触发远程上传的 Segment 大小阈值
index.hybrid_replication.segment_size_threshold: "50mb"

# P2P 复制超时时间
index.hybrid_replication.peer_copy_timeout: "500ms"

# P2P 复制最大并发 Segment 数
index.hybrid_replication.peer_copy_max_concurrent: 5

# P2P 复制带宽限制（单节点）
index.hybrid_replication.peer_copy_rate_limit: "200mb/s"

# Merge 后多久上传远程（延迟上传以等待可能的再次 merge）
index.hybrid_replication.upload_delay_after_merge: "5s"

# Elastic 节点 checkpoint 刷新间隔
index.hybrid_replication.remote_checkpoint_interval: "30s"

# 本地未上传 Segment 累积上限（超过则背压 merge）
index.hybrid_replication.local_pending_upload_limit: "5gb"

# Fixed Replica 本地小 Segment 保留上限
# 超过此值时强制触发 merge（即使 Lucene 不认为需要）
index.hybrid_replication.local_segment_retention_limit: "2gb"

# ─── 节点角色配置 ───

# Write 节点（持有 Primary）
node.roles: ["data_hot", "ingest"]
node.attr.tier: "write"

# Fixed Search 节点（持有实时 Replica）
node.roles: ["data_hot", "search"]
node.attr.tier: "realtime_search"

# Elastic Search 节点（从远程加载）
node.roles: ["search"]
node.attr.tier: "elastic_search"
node.search.cache.size: "80%"  # FileCache 尽量大
```

#### 12.11.12 与其他方案的组合

```
┌─────────────────────────────────────────────────────────────────┐
│     Hybrid Replication 可以与前述方案组合形成最优解               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  组合 1: Hybrid Replication + 方案 C（混合时间窗口）              │
│  ─────────────────────────────────────────────────               │
│  热窗口索引: Hybrid Replication（实时 + 弹性）                    │
│  温窗口索引: 纯 Remote Store（已不活跃，节省成本）                │
│  效果: 最近 2h 高频更新享受 P2P 实时性                           │
│        > 2h 的索引降级为纯远程模式释放资源                        │
│                                                                  │
│  组合 2: Hybrid Replication + 方案 D（搜索路由）                  │
│  ─────────────────────────────────────────────                   │
│  核心搜索: 路由到 Fixed Replica (P2P 实时)                        │
│  洪峰搜索: 路由到 Elastic Nodes (远程延迟)                        │
│  配合搜索路由插件动态分流                                         │
│  效果: 实时性有保证的同时具备弹性应对突发流量                      │
│                                                                  │
│  组合 3: Hybrid Replication + 电商大促（第 11 章）                │
│  ───────────────────────────────────────────────                 │
│  日常: 2 Write + 3 Fixed Replica（低成本高实时）                  │
│  大促: 弹性扩出 20 Elastic Nodes（利用远程大 Segment）            │
│  大促结束: Elastic Nodes 缩容，零数据丢失                         │
│  效果: 日常实时性不受影响 + 大促弹性应对                          │
│                                                                  │
│  推荐最终架构（高频更新 + 实时 + 弹性 全要）：                    │
│  ─────────────────────────────────────────────                   │
│                                                                  │
│    Hybrid Replication                                            │
│      + 业务层 Update Batcher（减少实际写入频率）                  │
│      + 搜索路由（核心走 Fixed，洪峰走 Elastic）                   │
│      + 大促时弹性扩 Elastic Nodes                                │
│      = 1s 实时 + 远程弹性 + 90%+ 成本节省                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 13. 云盘存储场景下的 IO 优化方案

### 13.1 问题背景

#### 13.1.1 云盘 vs 本地 NVMe 的 IO 特性差异

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  存储介质 IO 特性对比                                      │
├────────────────────┬──────────────────┬──────────────────────────────────┤
│ 指标               │ 本地 NVMe SSD     │ 云盘 (EBS/云硬盘/ESSD)           │
├────────────────────┼──────────────────┼──────────────────────────────────┤
│ 随机读延迟         │ 50-100 μs         │ 200-500 μs (3-10x)              │
│ 随机写延迟         │ 10-30 μs          │ 200-1000 μs (10-30x)            │
│ 顺序读带宽         │ 3-7 GB/s          │ 0.2-1 GB/s (5-30x)             │
│ 顺序写带宽         │ 2-5 GB/s          │ 0.2-0.5 GB/s (5-20x)           │
│ IOPS (4KB 随机)    │ 500K-1M           │ 10K-100K (5-50x)               │
│ fsync 延迟         │ 10-50 μs          │ 1-10 ms (100x!)                │
│ 尾延迟 (P99)       │ 稳定              │ 抖动大（网络+多租户）            │
│ 带宽计费           │ 无                │ 超额后限速/限 IOPS              │
│ 并发 IO 队列深度   │ 硬件多队列         │ 网络受限，深队列收益递减         │
└────────────────────┴──────────────────┴──────────────────────────────────┘
```

#### 13.1.2 ES 对 IO 的依赖路径分析

```
┌─────────────────────────────────────────────────────────────────────────┐
│              ES 核心 IO 热路径                                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  写入路径 (延迟敏感):                                                     │
│  ───────────────────                                                     │
│  1. Translog 写入 + fsync                                                │
│     └── TranslogWriter.syncUpTo() → channel.force(false)                 │
│     └── Checkpoint 写入 → checkpointChannel.force(false)                 │
│     └── 每次 index 请求都要等 fsync 完成（durability=request 时）          │
│     └── 云盘上 fsync 延迟 1-10ms → 直接决定写入延迟下限                   │
│                                                                          │
│  2. Lucene Segment 写入 (flush/refresh)                                  │
│     └── IndexWriter.commit() → 生成新 Segment 文件                       │
│     └── 顺序写，但文件较大时受带宽限制                                    │
│                                                                          │
│  3. Merge 写入                                                           │
│     └── ConcurrentMergeScheduler → 后台线程大量顺序 IO                   │
│     └── 读 N 个源 Segment + 写 1 个目标 Segment                          │
│     └── IO 密集型，可占满云盘带宽                                         │
│                                                                          │
│  读取路径 (吞吐敏感):                                                     │
│  ───────────────────                                                     │
│  4. 搜索读取 — 倒排索引/DocValues/KNN Vector                             │
│     └── MMapDirectory: 依赖 Page Cache，miss 时触发磁盘读                │
│     └── 随机读取模式（倒排跳跃、DocValues 按 docId 访问）                 │
│     └── 云盘随机读 200-500μs → 每次 cache miss 代价高 5-10x               │
│                                                                          │
│  5. Stored Fields 读取 (_source 返回)                                    │
│     └── NIOFSDirectory 读取 .fdt 文件                                    │
│     └── 按 docId 定位，本质是随机读                                       │
│     └── 大量 hits 返回时成为瓶颈                                          │
│                                                                          │
│  6. Recovery / Peer Recovery                                             │
│     └── 全量读取 Segment 文件 → 受顺序读带宽限制                         │
│     └── 云盘 0.2-1 GB/s → 100GB 数据恢复需 100-500s                      │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.2 优化方向一：Translog fsync 优化

#### 13.2.1 问题分析

```
源码路径: TranslogWriter.syncUpTo() (line 468)

当前行为：
1. writeLock 保护下把 buffer 数据写入 FileChannel
2. 释放 writeLock 后执行 channel.force(false) ← 这是核心延迟点
3. 写 Checkpoint 文件并再次 force(false)

一次 sync 操作 = 2 次 fsync (translog 文件 + checkpoint 文件)
云盘上: 2 × 1-10ms = 2-20ms 仅用于持久化

index 请求延迟 = 文档处理 + translog sync + response
在云盘上: ~1ms + 2-20ms + ~0.5ms ≈ 3-21ms（fsync 占 90%+!）
```

#### 13.2.2 优化方案

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  Translog fsync 优化方案                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  方案 1: 异步持久化 (最简单，直接配置)                                    │
│  ─────────────────────────────────────                                   │
│  index.translog.durability: "async"                                      │
│  index.translog.sync_interval: "5s"                                      │
│                                                                          │
│  效果: 写入延迟从 2-20ms → <1ms                                          │
│  代价: 宕机丢失最近 5s 数据                                              │
│  适用: 可从消息队列重放的场景                                             │
│                                                                          │
│  ─────────────────────────────────────────────────────────────────────   │
│                                                                          │
│  方案 2: Group Commit (批量 fsync)                                       │
│  ─────────────────────────────────                                       │
│  原理: 多个写入请求共享一次 fsync                                         │
│  ES 已有此机制: syncUpTo() 的 syncLock 保证同一时刻只有一个 fsync         │
│  等待中的请求会被后续 fsync "捎带" 持久化                                │
│                                                                          │
│  优化点: 在 sync 前增加短暂等待，积累更多请求                             │
│                                                                          │
│  index.translog.sync_interval: "100ms"   # 异步模式下的 sync 间隔        │
│  # 或自定义: 等待 50ms 或 积满 100 个请求 后触发 group fsync              │
│                                                                          │
│  效果: N 个请求共享 1 次 fsync → 均摊延迟 = fsync_time / N               │
│  例: 100 请求/50ms 窗口 × 5ms fsync = 均摊 0.05ms/请求                   │
│                                                                          │
│  ─────────────────────────────────────────────────────────────────────   │
│                                                                          │
│  方案 3: Translog 写入与 Checkpoint 分离                                  │
│  ────────────────────────────────────────                                │
│  现状: 每次 sync = fsync(translog) + fsync(checkpoint) = 2 次磁盘强制刷   │
│                                                                          │
│  优化: Checkpoint 不每次 fsync，改为定时刷新                              │
│  • translog 数据本身 append-only，即使 checkpoint 落后                    │
│  • 恢复时可扫描 translog 确定实际写入位置                                │
│  • checkpoint 每 N 次 sync 或每 T 秒才 fsync 一次                        │
│                                                                          │
│  效果: 每次 sync 从 2 次 fsync → 1 次 fsync                              │
│  风险: 恢复时需要额外的 translog 尾部验证逻辑                             │
│                                                                          │
│  ─────────────────────────────────────────────────────────────────────   │
│                                                                          │
│  方案 4: 利用云盘 io_uring / AIO 特性                                    │
│  ─────────────────────────────────────                                   │
│  现状: Java FileChannel.force() 是同步阻塞调用                           │
│  优化: 使用 JNI 封装 io_uring 提交异步 fsync                             │
│  • 一次系统调用提交多个文件的 fsync 请求                                  │
│  • 内核批量处理，减少用户态/内核态切换                                    │
│  • 适合云盘后端批量处理刷盘请求的特点                                     │
│                                                                          │
│  实现复杂度: 高（需 JNI + native 代码）                                   │
│  收益: 在高并发写入下可提升 20-40% 的 fsync 吞吐                         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.3 优化方向二：Directory / MMap 策略调优

#### 13.3.1 当前 ES 的 Directory 选择逻辑

```
源码: FsDirectoryFactory.java (line 77-113)

┌──────────────────────────────────────────────────────────────────────┐
│  ES 默认: index.store.type = "fs" → 自动选择 HYBRIDFS                 │
│                                                                       │
│  HYBRIDFS 的文件路由策略 (HybridDirectory.useDelegate):               │
│  ─────────────────────────────────────────────────────                │
│  走 MMapDirectory (mmap) 的文件:                                      │
│  ├── .doc (Frequencies/Postings)     ← 倒排主文件，搜索热路径         │
│  ├── .dvd (DocValues)                ← 排序/聚合热路径                │
│  ├── .nvd (Norms)                    ← 评分需要                      │
│  ├── .tim (Term Dictionary)          ← 词典查找热路径                 │
│  ├── .tip (Term Index)               ← 词典索引                      │
│  ├── .kdd/.kdi (Points/KD-tree)      ← 数值范围查询                  │
│  ├── .vec (Vector Data)              ← KNN 向量                      │
│  ├── .vex (Vector Index)             ← KNN 索引                      │
│  ├── .cfs (Compound Files)           ← 小 Segment 打包文件           │
│  └── .bfi (BloomFilter)              ← 布隆过滤器                    │
│                                                                       │
│  走 NIOFSDirectory (标准文件 IO) 的文件:                              │
│  ├── .fdt (Stored Fields Data)       ← _source 数据，随机大文件      │
│  ├── .fdx (Stored Fields Index)      ← 定位索引                      │
│  ├── .pos (Positions)                ← 位置信息                      │
│  ├── .pay (Payloads)                 ← 载荷                          │
│  ├── .tvd/.tvf/.tvx (Term Vectors)   ← 词向量                       │
│  └── .liv (Live Documents)           ← 删除标记                      │
│                                                                       │
│  关键发现: MMap 文件依赖 Page Cache，云盘下 cache miss 代价高          │
│  NIO 文件走 pread() 系统调用，每次是真正的磁盘 IO                     │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

#### 13.3.2 云盘场景的 MMap 优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│              云盘下 MMap 的问题与优化                                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  问题:                                                                    │
│  ─────                                                                   │
│  MMap 依赖 OS Page Cache，当工作集 > 可用内存时:                          │
│  • 频繁 page fault → 触发云盘随机读（200-500μs/次）                      │
│  • OS LRU 淘汰策略不感知 ES 访问模式                                      │
│  • 大 merge 读取旧 Segment → 污染 Page Cache → 搜索热数据被淘汰          │
│  • 云盘 P99 延迟抖动 → 搜索延迟尖刺                                      │
│                                                                          │
│  优化 1: index.store.preload 预加载关键文件                               │
│  ──────────────────────────────────────────                              │
│  # 预加载倒排和词典（搜索核心路径），不预加载大文件                        │
│  index.store.preload: ["tip", "tim", "doc", "dvd", "nvd"]               │
│                                                                          │
│  原理: Segment 打开时调用 madvise(MADV_WILLNEED) 触发预读                │
│  效果: 新 Segment 可见后立即开始后台加载到 Page Cache                     │
│  注意: 不要预加载 .fdt (stored fields) — 文件太大，加载反而挤占缓存       │
│                                                                          │
│  优化 2: 控制 MADV_RANDOM 行为                                            │
│  ────────────────────────────                                            │
│  源码 (line 59): MADV_RANDOM_FEATURE_FLAG                                │
│  • 默认关闭: disableRandomAdvice() → 所有读都走 MADV_NORMAL              │
│  • MADV_NORMAL: OS 预读 128KB 对齐块                                     │
│  • MADV_RANDOM: 禁止预读，只加载精确页                                   │
│                                                                          │
│  云盘最优策略:                                                            │
│  • 对倒排索引(.doc/.tim/.tip): 保持 MADV_NORMAL                          │
│    → 搜索通常顺序扫描 posting list，预读有效                              │
│  • 对 DocValues(.dvd): 保持 MADV_NORMAL                                  │
│    → 聚合是顺序扫描                                                      │
│  • 对 KNN Vector(.vec/.vex): 考虑 MADV_RANDOM                            │
│    → HNSW 图遍历是随机跳跃，预读大概率浪费带宽                            │
│                                                                          │
│  优化 3: JVM 堆外内存池替代部分 MMap                                     │
│  ────────────────────────────────────                                    │
│  对于工作集明确的热数据（如 Term Index .tip 文件通常较小）:               │
│  • 启动时全量读入 Direct ByteBuffer                                      │
│  • 不再依赖 Page Cache → 无 eviction 风险                                │
│  • 适合: .tip（通常几十 MB）、.bfi（BloomFilter）                        │
│  • 不适合: .dvd（可能几 GB）                                             │
│                                                                          │
│  优化 4: 大内存机器关闭 Swap                                             │
│  ──────────────────────────                                              │
│  bootstrap.memory_lock: true                                             │
│  # 或操作系统级别                                                        │
│  vm.swappiness = 1                                                       │
│                                                                          │
│  原因: 云盘已经是远程存储，如果 JVM Heap 被 swap 到云盘                   │
│        → GC 扫描需要走网络 IO → Stop-the-World 时间爆炸                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 13.3.3 何时放弃 MMap 改用 NIOFSDirectory

```
┌─────────────────────────────────────────────────────────────────────────┐
│  场景: 工作集远大于可用内存 (数据量 > 内存 3x 以上)                       │
│                                                                          │
│  问题: MMap 造成大量 major page fault，云盘随机读性能差                    │
│  表现: 搜索延迟 P99 飙升到 100ms+，持续抖动                              │
│                                                                          │
│  选项: index.store.type: "niofs"                                         │
│                                                                          │
│  NIOFSDirectory 的优势（云盘场景）:                                       │
│  ├── 读取通过显式 pread() — 可以自行控制 IO 调度                          │
│  ├── 不占用 Page Cache 配额（OS 可能缓存也可能不缓存）                    │
│  ├── 配合自定义读取缓存层 → 精确控制哪些数据在内存                        │
│  └── 无 SIGBUS 风险（MMap 在 IO 出错时可能产生信号导致 JVM 崩溃）         │
│                                                                          │
│  NIOFSDirectory 的劣势:                                                   │
│  ├── 每次读取都是系统调用（MMap 缓存命中时是纯内存访问）                   │
│  ├── 无法利用 OS 的预读优化                                              │
│  └── 需要显式管理 read buffer                                            │
│                                                                          │
│  推荐: 仅当内存严重不足 且 云盘随机读延迟 > 300μs 时考虑切换              │
│  大多数场景保持 HYBRIDFS + preload 调优即可                               │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.4 优化方向三：Merge IO 优化

#### 13.4.1 Merge 对云盘 IO 的冲击

```
问题量化:
├── merge 10 个 100MB Segment → 读 1GB + 写 1GB = 2GB IO
├── 默认 max_thread_count = min(4, CPU/2) → 可能 4 个 merge 并发
├── 瞬间 IO 负载: 4 × 2GB = 8GB 数据需要通过云盘
├── 云盘带宽 500MB/s → merge 持续 16s 占满带宽
├── 这 16s 内搜索的 page fault 全部排队 → 搜索延迟飙升
└── 更严重: 云盘 IOPS quota 耗尽 → 被限流 → 所有 IO 变慢

源码: MergeSchedulerConfig.java (line 46-48)
默认: max_thread_count = Math.max(1, Math.min(4, allocatedProcessors / 2))
注释明确说: "which works well for a good solid-state-disk (SSD)"
→ 这个默认值是为本地 NVMe 设计的，不适合云盘！
```

#### 13.4.2 Merge 调度优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Merge IO 优化方案                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  优化 1: 降低并发 merge 线程数                                            │
│  ─────────────────────────────────                                       │
│  index.merge.scheduler.max_thread_count: 1                               │
│  index.merge.scheduler.max_merge_count: 3                                │
│                                                                          │
│  原因: 云盘不是本地 SSD，并发 merge 无法利用 NVMe 多队列                  │
│  反而造成 IO 争抢，降低搜索和写入的 IO 质量                               │
│  max_merge_count=3: 允许排队 3 个 merge 但只有 1 个执行                   │
│                                                                          │
│  优化 2: Merge 限速 (auto_throttle)                                      │
│  ───────────────────────────────────                                     │
│  index.merge.scheduler.auto_throttle: true  (默认已开启)                 │
│                                                                          │
│  Lucene ConcurrentMergeScheduler 内置自适应限速:                         │
│  • 初始 IO rate: 20 MB/s                                                │
│  • 当 merge 落后时自动提速（最高 10 GB/s → 对云盘不现实）                │
│  • 当 merge 追上时自动降速                                               │
│                                                                          │
│  问题: 自适应算法不感知云盘 IOPS quota，可能"提速"后触发限流              │
│                                                                          │
│  增强优化: 手动设定 merge 带宽上限                                        │
│  indices.store.throttle.max_bytes_per_sec: "100mb"                       │
│  # 限制 merge IO 不超过云盘总带宽的 20-30%                               │
│  # 留 70-80% 带宽给搜索和写入                                            │
│                                                                          │
│  优化 3: Merge IO 优先级降低 (Linux ioprio)                              │
│  ──────────────────────────────────────────                              │
│  在 OS 层面为 merge 线程设置较低 IO 优先级:                               │
│  • Merge 线程设为 ionice class 2 (best-effort), priority 7 (最低)        │
│  • 搜索/写入线程保持默认 priority 4                                      │
│  • 云盘驱动器的 IO 调度器优先处理高优先级请求                             │
│                                                                          │
│  实现方式: JNI 调用 ioprio_set() 或启动脚本中设置 cgroup:                 │
│  # cgroup v2 设置 merge 线程 IO 权重                                     │
│  echo "100:200" > /sys/fs/cgroup/system.slice/elasticsearch/io.weight    │
│                                                                          │
│  优化 4: 错峰 merge                                                      │
│  ─────────────────                                                       │
│  # 非高峰时段执行大 merge，高峰时段抑制                                   │
│  # 通过动态设置控制:                                                     │
│  # 高峰时段                                                              │
│  PUT /_cluster/settings                                                  │
│  { "transient": {                                                        │
│    "index.merge.scheduler.max_thread_count": 0,                          │
│    "index.merge.policy.max_merged_segment": "100mb"                      │
│  }}                                                                      │
│  # 低峰时段                                                              │
│  PUT /_cluster/settings                                                  │
│  { "transient": {                                                        │
│    "index.merge.scheduler.max_thread_count": 2,                          │
│    "index.merge.policy.max_merged_segment": "5gb"                        │
│  }}                                                                      │
│                                                                          │
│  优化 5: Merge 读取使用 Direct IO (bypass page cache)                    │
│  ─────────────────────────────────────────────────────                   │
│  原理: Merge 读取的源 Segment 大部分即将被删除                            │
│  这些数据不应该挤入 Page Cache 污染搜索热数据                             │
│                                                                          │
│  实现: 自定义 Directory 对 IOContext.MERGE 使用 O_DIRECT                  │
│  • 读取: 使用 DirectByteBuffer 对齐到 4KB 边界                           │
│  • 写入: merge 产物仍然走 Page Cache（后续搜索要用）                      │
│                                                                          │
│  效果: 避免 merge 读取 "冲洗" 搜索热数据的 Page Cache                    │
│  注意: 需要 JDK 17+ 的 FileChannel 支持 O_DIRECT flag                    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.5 优化方向四：写入 IO Buffer 优化

#### 13.5.1 DiskIoBufferPool 分析

```
源码: DiskIoBufferPool.java

当前设计:
├── 默认 buffer 大小: 64KB (可通过 -Des.disk_io.direct.buffer.size 调整)
├── ThreadLocal<ByteBuffer> — 每个写入线程一个 Direct ByteBuffer
├── 仅 write/flush 线程池才分配 Direct Buffer，其他线程走 heap buffer
├── Translog 写入: 数据 → buffer 攒批 → 通过 DirectBuffer 写入 FileChannel
└── 64KB 的 buffer 意味着可能多次 write() 系统调用

云盘优化点:
─────────────
云盘的最优 IO 大小通常是 128KB-256KB（网络包对齐 + 后端条带化）
64KB 可能导致:
• 更多系统调用次数
• 云盘后端无法充分利用条带化并行
• 小 IO 的 IOPS 消耗高于大 IO

优化: 增大 Direct IO Buffer
-Des.disk_io.direct.buffer.size=256KB

效果: 减少写入系统调用次数 4x，单次写入更大块 → 云盘后端更高效
```

#### 13.5.2 Translog 写入合并优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│              Translog Buffer 优化                                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  源码: TranslogWriter.add() (line 238)                                   │
│                                                                          │
│  当前行为:                                                                │
│  1. 检查 buffer 是否超过 forceWriteThreshold                             │
│  2. 如果超过 → 立即刷写到 FileChannel (writeBufferedOps)                 │
│  3. 数据追加到 buffer                                                    │
│                                                                          │
│  forceWriteThreshold 默认值: ByteSizeValue.ofKb(64)                      │
│  → 攒满 64KB 就触发一次写入                                              │
│                                                                          │
│  优化: 增大写入缓冲区阈值                                                │
│  ──────────────────────────                                              │
│  # 针对云盘增大 buffer 到 256KB-1MB                                      │
│  index.translog.buffer_size: "256kb"                                     │
│                                                                          │
│  原理:                                                                    │
│  • 积累更多操作后一次性写入 → 减少 write() 系统调用                      │
│  • 大块顺序写对云盘更友好（减少 IOPS 消耗）                              │
│  • sync 时再统一 fsync → 同样的 fsync 次数但每次 fsync 的数据更多        │
│                                                                          │
│  注意:                                                                    │
│  • 不影响持久性（fsync 是独立控制的）                                     │
│  • 增大 buffer = 增大内存占用（每 shard 一个 buffer）                     │
│  • 100 shards × 256KB = 25MB — 可接受                                    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.6 优化方向五：Page Cache 管理

#### 13.6.1 Page Cache 争抢问题

```
┌─────────────────────────────────────────────────────────────────────────┐
│           云盘下 Page Cache 是核心瓶颈                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  本地 SSD: cache miss → 50-100μs → 影响小                                │
│  云盘:    cache miss → 200-500μs → 影响 5x                               │
│                                                                          │
│  所以 Page Cache 命中率在云盘场景下影响被放大 5-10 倍                      │
│                                                                          │
│  典型 Page Cache 争抢场景:                                                │
│  ├── Merge 读取旧 Segment → 挤入 cache → 淘汰搜索热数据                  │
│  ├── Recovery 全量读取 → 大面积冲洗 cache                                │
│  ├── 冷数据搜索（偶发）→ 加载冷数据 → 淘汰热数据                         │
│  └── OS 后台 dirty page writeback → 占用 IO 带宽                         │
│                                                                          │
│  量化:                                                                    │
│  假设 32GB 内存机器:                                                      │
│  • JVM Heap: 16GB                                                        │
│  • OS + 其他: 2GB                                                        │
│  • 可用 Page Cache: 14GB                                                 │
│  • 索引总 Segment 大小: 100GB                                            │
│  • Cache 覆盖率: 14%                                                     │
│  • 搜索热路径文件(.doc + .tim + .tip + .dvd): 约 40% = 40GB             │
│  • 热文件 cache 覆盖率: 14/40 = 35%                                     │
│  • 65% 的搜索读取是 cache miss → 走云盘 → 200-500μs                      │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 13.6.2 Page Cache 优化策略

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Page Cache 优化                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  策略 1: 降低 JVM Heap，让更多内存给 Page Cache                           │
│  ────────────────────────────────────────────                            │
│  经验法则（云盘场景）:                                                    │
│  • JVM Heap ≤ min(26GB, 物理内存 × 40%)                                  │
│  • Page Cache ≥ 物理内存 × 50%                                           │
│                                                                          │
│  例: 64GB 机器                                                           │
│  • Heap: 26GB (CMS/G1 指针压缩上限)                                     │
│  • Page Cache: ~36GB                                                     │
│  • 可缓存更多 Segment 热数据                                             │
│                                                                          │
│  策略 2: 使用 index.store.preload 精准预加载                              │
│  ──────────────────────────────────────────                              │
│  # 只预加载搜索关键路径文件                                               │
│  index.store.preload: ["tip", "tim", "doc", "nvd"]                       │
│                                                                          │
│  不要预加载:                                                              │
│  • .dvd (DocValues) — 如果不是所有字段都做聚合，按需加载更好              │
│  • .fdt (Stored Fields) — 太大                                           │
│  • .vec/.vex (Vectors) — 太大，且遍历模式特殊                            │
│                                                                          │
│  策略 3: posix_fadvise 控制缓存策略                                       │
│  ──────────────────────────────────                                      │
│  对不同 IO 操作提示 OS 缓存行为:                                          │
│                                                                          │
│  • Merge 读取: POSIX_FADV_NOREUSE                                       │
│    → 告诉 OS 这些页面用完即可释放                                        │
│    → 不要占用 LRU 队列位置                                               │
│                                                                          │
│  • Recovery 读取: POSIX_FADV_SEQUENTIAL + POSIX_FADV_NOREUSE             │
│    → 大块预读 + 用完即释放                                               │
│                                                                          │
│  • 搜索读取: POSIX_FADV_WILLNEED (预加载时)                              │
│    → 主动预取到 cache                                                    │
│                                                                          │
│  实现: 需要通过 JNI 调用 posix_fadvise()                                 │
│  或利用 Lucene MMapDirectory 的 madvise hint                             │
│                                                                          │
│  策略 4: OS 内核参数调优                                                  │
│  ─────────────────────                                                   │
│  # 减少 dirty page 回写延迟（避免突发大量回写占带宽）                     │
│  vm.dirty_ratio = 10              # 默认 20，降低到 10                   │
│  vm.dirty_background_ratio = 5    # 默认 10，更早开始后台回写             │
│  vm.dirty_expire_centisecs = 1000 # 默认 3000，脏页 10s 就开始写         │
│                                                                          │
│  # 减少 Page Cache 回收压力                                              │
│  vm.vfs_cache_pressure = 50       # 默认 100，减少 dentry/inode 回收     │
│                                                                          │
│  # 关闭 THP (Transparent Huge Pages)                                    │
│  echo never > /sys/kernel/mm/transparent_hugepage/enabled                │
│  原因: THP 合并时可能短暂 stall + 大页 eviction 粒度太大                 │
│                                                                          │
│  策略 5: cgroup v2 内存隔离                                               │
│  ──────────────────────────                                              │
│  对 ES 进程设置 memory.high:                                             │
│  • 确保 ES 获得稳定的 Page Cache 配额                                    │
│  • 防止同机其他进程抢走 cache                                            │
│  • 特别重要: 多 ES 实例共存时                                            │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.7 优化方向六：Lucene Segment 组织策略

#### 13.7.1 减少文件数量

```
┌─────────────────────────────────────────────────────────────────────────┐
│            减少 Segment 文件数 — 降低 IOPS 消耗                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  一个 Segment 包含的文件数:                                               │
│  ├── .si (segment info)                                                  │
│  ├── .doc (postings)                                                     │
│  ├── .tim + .tip (term dictionary + index)                               │
│  ├── .dvd + .dvm (doc values + metadata)                                │
│  ├── .nvd + .nvm (norms + metadata)                                     │
│  ├── .fdt + .fdx + .fdm (stored fields)                                 │
│  ├── .pos + .pay (positions + payloads)                                  │
│  ├── .kdd + .kdi + .kdm (points)                                        │
│  ├── .vec + .vex + .vem (vectors)                                        │
│  └── .liv (live docs)                                                    │
│  = 15-20 个文件 / Segment                                                │
│                                                                          │
│  100 个 Segment × 20 文件 = 2000 个文件需要打开                          │
│  每次搜索打开一个 IndexInput = 一次 open() 系统调用                       │
│  云盘 open() = 元数据操作 → 也有延迟                                     │
│                                                                          │
│  优化 1: 使用 Compound File Format (CFS)                                 │
│  ─────────────────────────────────────────                               │
│  index.compound_format: true  (小 Segment 默认已启用)                    │
│                                                                          │
│  CFS 把一个 Segment 的所有文件打包为 .cfs + .cfe 两个文件                │
│  • 20 个文件 → 2 个文件                                                  │
│  • 减少文件句柄数                                                        │
│  • 减少元数据操作                                                        │
│  • 代价: 读取时多一次间接层（cfe 索引查找）                              │
│                                                                          │
│  注意: 大 Segment (> 100MB) 默认不使用 CFS                               │
│  因为 CFS 内的文件无法独立 mmap → 预加载粒度变粗                         │
│  云盘场景可适当放大 CFS 阈值:                                             │
│  index.compound_format: 0.5  (50% 的 Segment 用 CFS)                    │
│                                                                          │
│  优化 2: 减少 Segment 数量                                               │
│  ──────────────────────────                                              │
│  # 提高 refresh_interval 减少小 Segment                                  │
│  index.refresh_interval: "5s"  (云盘下 1s 不现实)                        │
│                                                                          │
│  # 提高 flush_threshold_size 减少 flush 产生的 Segment                   │
│  index.translog.flush_threshold_size: "1gb"                              │
│                                                                          │
│  # 减少 merge policy 的 segments_per_tier                                │
│  index.merge.policy.segments_per_tier: 5  (默认 10)                      │
│  → 更积极 merge → 更少 Segment 存在 → 搜索时打开更少文件                 │
│  → 代价: merge 更频繁 → IO 更多（需平衡）                                │
│                                                                          │
│  优化 3: max_merged_segment 与云盘对齐                                   │
│  ────────────────────────────────────                                    │
│  index.merge.policy.max_merged_segment: "5gb"                            │
│  • 允许更大的 Segment → 更少的 Segment 数量                              │
│  • 搜索时需要遍历的 Segment 更少                                         │
│  • 单次搜索的文件 IO 操作数减少                                           │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.8 优化方向七：搜索读取路径优化

#### 13.8.1 Stored Fields (_source) 读取优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│         Stored Fields 读取是云盘下最痛的 IO 路径                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  为什么 Stored Fields 在云盘上最慢:                                       │
│  ─────────────────────────────────                                       │
│  1. .fdt 文件通过 NIOFSDirectory 读取（不走 MMap）                        │
│  2. 每个 hit 需要一次精确位置的 pread() → 随机读                         │
│  3. 返回 100 hits = 100 次随机读 × 200-500μs = 20-50ms 仅用于取 _source  │
│  4. .fdt 文件很大（可能几 GB）→ Page Cache 命中率低                       │
│  5. 压缩块（16KB/block）→ 读一个文档可能加载不需要的数据                  │
│                                                                          │
│  优化 1: 减少 _source 返回量                                             │
│  ──────────────────────────                                              │
│  • _source: false + 使用 docvalue_fields 返回                            │
│  • 只返回需要的字段: "_source": {"includes": ["title", "price"]}         │
│  • 配合 synthetic _source (ES 8.4+):                                    │
│    从 doc_values 重建 _source → 走 MMap + 顺序读 → 快得多                │
│                                                                          │
│  优化 2: stored fields 压缩级别                                          │
│  ──────────────────────────────                                          │
│  index.codec: "best_compression" (DEFLATE, 更小但更慢解压)               │
│  vs                                                                      │
│  index.codec: "default" (LZ4, 快速解压但文件稍大)                        │
│                                                                          │
│  云盘推荐: "best_compression"                                            │
│  • 文件更小 → 更多数据能缓存在 Page Cache                                │
│  • 减少实际 IO 量                                                        │
│  • CPU 解压时间 vs 云盘 IO 时间: 前者可忽略                              │
│  • 典型压缩率提升: 30-50% 更小                                           │
│                                                                          │
│  优化 3: 预取策略                                                        │
│  ────────────────                                                        │
│  搜索时可以预测需要取 _source 的 docId 列表                              │
│  在评分阶段完成后、取 source 前，批量发起 readahead:                     │
│  • 排序后取 top-K 的 docId → 计算 .fdt 中的偏移量                        │
│  • 批量发起 posix_fadvise(WILLNEED) 或 madvise                          │
│  • 然后顺序读取 — 让 OS 有时间预取                                       │
│                                                                          │
│  优化 4: 更大的 stored fields 块大小                                     │
│  ──────────────────────────────────                                      │
│  Lucene Stored Fields 默认 block size: 16KB (压缩块)                     │
│  云盘最优 IO 大小: 128-256KB                                             │
│  可以通过自定义 Codec 增大 block size:                                   │
│  • 减少随机读次数（一次读 128KB 包含更多文档）                            │
│  • 代价: 单文档读取时浪费更多带宽（读了不需要的文档）                     │
│  • 适合: 批量返回场景（如 scroll/search_after 大量取数据）                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 13.8.2 倒排索引读取优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│          倒排索引 (.doc/.tim/.tip) 搜索路径优化                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  优化 1: Term Index (.tip) 常驻内存                                      │
│  ─────────────────────────────────                                       │
│  .tip 文件特点:                                                          │
│  • 每个 Segment 一个，大小通常 1-50MB                                    │
│  • 搜索第一步: 在 .tip 中 FST 查找 → 确定 .tim 中的位置                 │
│  • 几乎每次搜索都要访问                                                  │
│                                                                          │
│  index.store.preload: ["tip"]  ← 最高优先级预加载                        │
│                                                                          │
│  进一步: 如果 .tip 总量 < 可用内存 10%，可以全量常驻                     │
│  10 Segments × 30MB = 300MB → 完全值得预加载                             │
│                                                                          │
│  优化 2: DocValues 列式顺序访问                                          │
│  ────────────────────────────                                            │
│  聚合查询（terms agg, metric agg）对 .dvd 文件是顺序扫描                │
│  云盘顺序读性能远好于随机读                                               │
│                                                                          │
│  确保 Doc Values 格式使用列式存储:                                        │
│  • 字段类型选择: keyword > text (keyword 原生支持 doc_values)            │
│  • 避免 fielddata: true (把倒排索引加载到内存做聚合 → 浪费)              │
│  • 使用 doc_values: true (默认) → .dvd 顺序读                           │
│                                                                          │
│  优化 3: 全局序号 (Global Ordinals) 缓存策略                             │
│  ─────────────────────────────────────────────                           │
│  # 避免每次搜索重建全局序号（会触发大量 .dvd 读取）                       │
│  eager_global_ordinals: true  # 在 refresh 时预构建                      │
│                                                                          │
│  • refresh 时后台加载 → 搜索时直接使用缓存                               │
│  • 代价: refresh 变慢（但可以容忍）                                      │
│  • 收益: 搜索时不再触发 .dvd 的大量读取                                  │
│                                                                          │
│  优化 4: 搜索并发度控制                                                  │
│  ──────────────────────                                                  │
│  # 每个搜索请求在单 shard 上的并发 slice 数                              │
│  # 默认根据 CPU 自动设定，云盘下应适当降低                                │
│  search.max_concurrent_shard_requests: 3  (集群级)                       │
│                                                                          │
│  原因: 太多并发搜索同时触发 page fault → 云盘 IOPS 爆炸                  │
│  降低并发 → 更好的 IO 聚合 → 单请求延迟反而更低                          │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.9 优化方向八：Recovery IO 优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│              Peer Recovery 在云盘下的优化                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  问题:                                                                    │
│  节点重启 / shard 迁移 → Peer Recovery → 全量读取 Segment 文件            │
│  • 源节点: 读取所有 Segment 发送给目标（顺序读，受云盘带宽限制）           │
│  • 目标节点: 接收写入磁盘（顺序写，受云盘带宽限制）                       │
│  • 同时: 源节点还在服务搜索 → IO 争抢                                    │
│                                                                          │
│  100GB shard，云盘读 500MB/s → Recovery 至少 200s                         │
│  期间搜索延迟可能翻倍（IO 争抢）                                          │
│                                                                          │
│  优化 1: Recovery 限速                                                    │
│  ─────────────────────                                                   │
│  indices.recovery.max_bytes_per_sec: "100mb"                             │
│  # 限制 recovery 只用 20% 的云盘带宽                                     │
│  # 防止 recovery 影响在线搜索                                            │
│  # 代价: recovery 时间变长 (100GB / 100MB/s = 1000s)                     │
│                                                                          │
│  优化 2: Recovery 并发文件数控制                                          │
│  ─────────────────────────────                                           │
│  indices.recovery.max_concurrent_file_chunks: 2  (默认 2)                │
│  indices.recovery.max_concurrent_operations: 1   (默认 1)                │
│  # 云盘下不要增大这些值 — 并发越高 IO 争抢越严重                          │
│                                                                          │
│  优化 3: 使用 Snapshot Recovery (index.recovery.type)                     │
│  ────────────────────────────────────────────────────                    │
│  如果有远程快照:                                                          │
│  • 从 S3/OSS 快照恢复而非 Peer Recovery                                  │
│  • 避免占用源节点的 IO 带宽                                              │
│  • 远程存储带宽通常比单个云盘节点更大                                     │
│  • 代价: 网络流量费用                                                    │
│                                                                          │
│  优化 4: 使用 Searchable Snapshot 避免 Recovery                           │
│  ─────────────────────────────────────────────                           │
│  • 冷数据使用 Searchable Snapshot mount                                  │
│  • 无需全量恢复数据 → 按需从远程加载                                     │
│  • Recovery 时间: 从分钟级 → 秒级（仅加载元数据）                        │
│  • 特别适合大容量冷数据在云盘场景                                         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.10 优化方向九：KNN 向量搜索 IO 优化

```
┌─────────────────────────────────────────────────────────────────────────┐
│             向量搜索是云盘最不友好的 IO 模式                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  HNSW 图搜索的 IO 特征:                                                  │
│  ─────────────────────                                                   │
│  • 从入口点开始，贪心遍历图的邻居节点                                     │
│  • 每跳: 读取一个向量（128D float = 512B）+ 读取邻居列表（~100B）         │
│  • 搜索一个向量: ~100-500 跳 → 100-500 次随机读                         │
│  • .vec 文件（向量数据）: 随机读，无局部性                                │
│  • .vex 文件（图结构）: 随机读，跳跃式遍历                                │
│                                                                          │
│  云盘上:                                                                  │
│  100 跳 × 200μs/次 (cache miss) = 20ms / query                          │
│  本地 NVMe: 100 跳 × 50μs/次 = 5ms / query                              │
│  差距: 4x                                                                │
│                                                                          │
│  优化策略:                                                                │
│  ──────────                                                              │
│  1. 向量数据必须常驻内存                                                  │
│     • KNN 索引是唯一"不可接受 cache miss"的场景                           │
│     • 确保 .vec + .vex 文件总大小 < 可用 Page Cache                      │
│     • 使用 preload: index.store.preload: ["vec", "vex"]                  │
│     • 如果放不下 → 使用量化 (PQ/SQ) 减少向量大小                         │
│                                                                          │
│  2. 使用标量量化 (Scalar Quantization)                                   │
│     • float32 → int8: 向量大小减少 4x                                    │
│     • .veq 文件大小 = 原来的 25%                                         │
│     • 更容易全量放入 Page Cache                                          │
│     • ES 8.x 已支持: index.knn.algo_param.sq_type: "fp16"              │
│                                                                          │
│  3. 增大 ef_construction / m 值换取更好的图局部性                         │
│     • 更高 m → 每个节点更多邻居 → 搜索跳数减少                           │
│     • 跳数减少 = 随机读次数减少 = 云盘 IO 次数减少                       │
│     • 代价: 索引更大 + 构建更慢                                          │
│                                                                          │
│  4. 按距离预排序向量存储（提升局部性）                                    │
│     • 使用 index sorting 按地理距离/embedding 相似度排序                  │
│     • 相似的向量在 .vec 文件中物理相邻                                    │
│     • 图遍历的邻居大概率在相邻物理位置 → 预读有效                        │
│     • 实现: routing 或 index sort by cluster_id                          │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.11 优化配置汇总 (Quick Reference)

```yaml
# ═══════════════════════════════════════════════════════════════
# ES 云盘 IO 优化配置 — 生产环境推荐值
# ═══════════════════════════════════════════════════════════════

# ─── 集群级 (elasticsearch.yml) ───

# 内存锁定，防止 swap
bootstrap.memory_lock: true

# Recovery 限速（保护在线搜索）
indices.recovery.max_bytes_per_sec: "100mb"

# JVM 参数 (jvm.options)
# -Xms26g -Xmx26g  # 留更多内存给 Page Cache
# -Des.disk_io.direct.buffer.size=256KB  # 增大 IO buffer

# ─── 索引级 (Index Settings) ───

# Translog 优化
index.translog.durability: "async"        # 或 "request" 按业务需要
index.translog.sync_interval: "5s"
index.translog.flush_threshold_size: "1gb"

# Refresh 优化（减少小 Segment）
index.refresh_interval: "5s"              # 云盘下 1s 太激进

# Merge 优化（降低 IO 争抢）
index.merge.scheduler.max_thread_count: 1
index.merge.scheduler.max_merge_count: 3
index.merge.scheduler.auto_throttle: true
index.merge.policy.max_merged_segment: "5gb"
index.merge.policy.segments_per_tier: 10
index.merge.policy.floor_segment: "50mb"  # 增大 floor 减少小 Segment

# 存储优化
index.store.type: "hybridfs"              # 保持默认
index.store.preload: ["tip", "tim", "doc", "nvd"]  # 预加载搜索热文件
index.codec: "best_compression"           # 压缩率更高 → IO 更少

# ─── OS 级 (sysctl) ───

vm.swappiness = 1
vm.dirty_ratio = 10
vm.dirty_background_ratio = 5
vm.dirty_expire_centisecs = 1000
vm.vfs_cache_pressure = 50
# echo never > /sys/kernel/mm/transparent_hugepage/enabled

# ─── 搜索优化 ───
search.max_concurrent_shard_requests: 3    # 降低 IO 并发争抢
```

### 13.12 优化效果预估

```
┌─────────────────────────────────────────────────────────────────────────┐
│               云盘 IO 优化效果预估                                         │
├──────────────────────┬──────────────────┬────────────────────────────────┤
│ 优化项               │ 预期收益          │ 实现难度                       │
├──────────────────────┼──────────────────┼────────────────────────────────┤
│ Translog async       │ 写入延迟 -90%    │ 低（配置项）                   │
│ Group Commit         │ 写入吞吐 +3-5x   │ 低（ES 内置）                  │
│ Preload 关键文件     │ 搜索 P50 -30%    │ 低（配置项）                   │
│ Merge 单线程化       │ 搜索 P99 -50%    │ 低（配置项）                   │
│ Refresh 5s           │ IO 量 -80%       │ 低（配置项）                   │
│ best_compression     │ IO 量 -30%       │ 低（配置项）                   │
│ 增大 IO buffer       │ 写入 IOPS -60%   │ 低（JVM 参数）                 │
│ OS 内核参数          │ 尾延迟 -20%      │ 低（sysctl）                   │
│ Merge Direct IO      │ Cache 命中率 +15%│ 高（需改代码）                 │
│ posix_fadvise        │ Cache 命中率 +10%│ 高（需 JNI）                   │
│ 向量量化             │ KNN 延迟 -60%    │ 中（需重建索引）               │
│ Searchable Snapshot  │ Recovery -95%    │ 中（架构变更）                 │
├──────────────────────┼──────────────────┼────────────────────────────────┤
│ 综合（仅配置项）     │ 吞吐 +3x         │ 低，30min 可完成               │
│                      │ 延迟 -50%        │                                │
├──────────────────────┼──────────────────┼────────────────────────────────┤
│ 综合（含代码改动）   │ 吞吐 +5x         │ 高，需要定制开发               │
│                      │ 延迟 -70%        │                                │
└──────────────────────┴──────────────────┴────────────────────────────────┘
```

### 13.13 监控指标 — 判断 IO 是否是瓶颈

```
# 关键监控指标与阈值

GET _nodes/stats/fs,indices

需关注指标:
├── fs.io_stats.total.operations        # 总 IOPS
├── fs.io_stats.total.read_kilobytes    # 读取带宽
├── fs.io_stats.total.write_kilobytes   # 写入带宽
├── indices.translog.uncommitted_size_in_bytes  # 未提交 translog 大小
├── indices.merges.current              # 正在进行的 merge 数
├── indices.merges.total_throttled_time # merge 被限速的时间
├── indices.refresh.total_time          # refresh 总耗时
└── indices.search.fetch_time           # fetch 阶段耗时（_source 读取）

OS 层面:
├── iostat -x 1
│   ├── await > 5ms      → 云盘延迟高
│   ├── %util > 80%      → IO 接近饱和
│   ├── avgqu-sz > 32    → IO 队列积压
│   └── r_await vs w_await → 区分读写瓶颈
├── vmstat 1
│   └── si/so > 0        → 发生了 swap（必须修复）
└── /proc/meminfo
    └── Cached / MemTotal < 30%  → Page Cache 不足

判断规则:
├── 如果 search.fetch_time / search.query_time > 2 → stored fields 读取是瓶颈
├── 如果 merges.total_throttled_time 持续增长 → merge IO 饱和
├── 如果 await > 10ms 且 %util > 90% → 云盘已达性能上限，需升配或架构调整
└── 如果 translog uncommitted 持续增长 → fsync 跟不上写入速度
```
