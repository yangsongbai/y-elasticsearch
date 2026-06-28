# Elasticsearch 存算分离设计方案

> 基于 ES 7.17.4,参考 OpenSearch Remote Store 架构,面向云原生部署的多场景通用工程设计方案。

---

## 第 1 章 设计原则与三层架构

### 1.1 设计原则

| 原则 | 说明 |
|------|------|
| Local-first 写入 | Primary 写本地 Lucene + Translog,Remote 异步 write-back |
| 分层读路由 | Hot=本地,Warm=Remote+Cache,Cold=Remote+小 Cache |
| 存储/计算独立伸缩 | 存储由 Remote 统一承载,计算节点按 QPS/延迟弹性 |
| 状态机驱动流转 | TieringService 原子流转,可中断/可回滚 |
| Lucene 透明 | 通过 Directory 抽象,Lucene 无感知 |
| 可降级 | Remote 故障不阻塞写入,背压 + 本地兜底 |

### 1.2 三层架构

```
┌──────────────────────────────────────────────────────────────────┐
│  Control Plane                                                    │
│   TieringService + AutoScaler + DesiredBalance Allocator          │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Hot Tier                                                         │
│   Primary shards, NVMe 本地, write-back 异步上传                  │
│   Lean Sync Replica(只存 tail + Remote FileCache)               │
│                                                                   │
│  Warm Tier                                                        │
│   Async Replica, SSD FileCache, Remote-First, LFU/Region         │
│   弹性伸缩(HPA 驱动)                                            │
│                                                                   │
│  Cold Tier                                                        │
│   Remote-First, 小 LRU Cache, 无 Prefetch                        │
│   高密度低成本部署                                                 │
│                                                                   │
├──────────────────────────────────────────────────────────────────┤
│  Remote Object Store (S3 / OSS / MinIO)                           │
│   统一存储:segments + translog + metadata                         │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 与同类方案对比

| 维度 | 本方案 | OpenSearch Remote Store | ES Frozen Tier | ES ILM |
|------|--------|------------------------|----------------|--------|
| 写入模型 | Write-Back 异步 | Write-Through 同步 | 不可写 | 不涉及 |
| 副本同步 | Segment Replication(默认) + Doc Repl(可选) | Segment Replication | N/A | Doc Replication |
| 热数据 | 本地 NVMe + LSR | 本地 NVMe | 本地 NVMe | 本地 NVMe |
| 温数据 | Remote + FileCache(弹性) | Remote + FileCache | N/A | 本地 HDD |
| 冷数据 | Remote + 小 FileCache | Remote + FileCache | Searchable Snapshot | Searchable Snapshot |
| 弹性伸缩 | 秒级(Warm 无状态) | 分钟级 | 手动 | 手动 |
| 层级流转 | 状态机 + ILM | 手动 + ILM | Snapshot mount | allocate action |

### 1.4 关键创新

**创新 1:Write-Back 异步上传 + Segment Replication 默认**

- Primary 写入不等待 Remote ACK,延迟与纯本地一致
- 副本通过 Remote Store Segment Replication 同步,Primary 不承担转发带宽
- Doc Replication 仅作为可选 per-index fallback(<100ms 可见场景)

**创新 2:Index-level "role routing"**

- Primary 只分配到 Hot 节点
- Sync Replica / LSR 分配到 Hot 节点(容灾)
- Async Replica 分配到 Warm 节点(弹性查询)
- 层级由 `index.routing.allocation.require._tier` 控制

**创新 3:Lean Sync Replica(LSR)**

- 副本只存储未上传到 Remote 的 tail 数据(几 GB vs 全量 TB 级)
- 通过 LayeredDirectory 统一暴露 tail + Remote 给 Lucene
- 存储成本降低 85%,副本提升 RTO 不变(<5s)

---

## 第 2 章 集群拓扑与节点角色设计

### 2.1 节点角色定义

| 角色 | node.roles | 主要职责 | 状态特性 |
|------|-----------|---------|---------|
| Master | [master] | 集群管理 | 有状态(ClusterState) |
| Coord | [] (空) | 查询路由聚合 | 无状态 |
| Hot Data | [data_hot, ingest] | Primary 写入 + 同步副本 | 有状态(本地+Remote) |
| Warm Data | [data_warm, search] | Replica 查询,弹性伸缩 | 准无状态(仅缓存) |
| Cold Data | [data_cold, search] | 低频查询,高密度部署 | 准无状态(小缓存) |

### 2.2 节点资源配置基线

| 角色 | 推荐机型 | CPU/Memory | 本地存储 | 网络 |
|------|---------|-----------|---------|------|
| Master | m6i.xlarge | 4C/16G | 100GB GP3 | 10Gbps |
| Coord | c6i.2xlarge | 8C/16G | 50GB GP3 | 12.5G |
| Hot Data | i4i.4xlarge | 16C/128G | 3.75TB NVMe | 25Gbps |
| Warm Data | r6id.xlarge | 4C/32G | 237GB NVMe | 12.5G |
| Cold Data | c6id.large | 2C/4G | 118GB NVMe | 12.5G |

### 2.3 标准生产拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│  AZ-1: Master-1, Coord-1, Hot-1(主分片), Warm-1(副本), Cold-1  │
├─────────────────────────────────────────────────────────────────┤
│  AZ-2: Master-2, Coord-2, Hot-2(主分片), Warm-2(副本), Cold-2  │
├─────────────────────────────────────────────────────────────────┤
│  AZ-3: Master-3, Coord-3                                        │
├─────────────────────────────────────────────────────────────────┤
│  Remote Object Store (S3 / OSS, 跨 AZ 冗余)                     │
└─────────────────────────────────────────────────────────────────┘

最小集群(POC):  Master×3, Coord×2, Hot×2, Warm×2, Cold×0  = 9 节点
生产推荐:       Master×3, Coord×3-6, Hot×3-9, Warm×3-50, Cold×2-10
```

### 2.4 分片分配规则

| 索引层级 | Primary 分配 | Replica 分配 | 副本数 |
|---------|-------------|-------------|--------|
| Hot | tier=hot | tier=hot(同步) + tier=warm(异步弹性) | 1 + 0-N |
| Warm | N/A(无主分片) | tier=warm | 0(Remote 兜底) |
| Cold | N/A | tier=cold | 0 |

### 2.5 弹性副本(Elastic Replica)机制

Hot 索引的副本可以分布在 Hot 和 Warm 两类节点上:

- **Synchronous Replica(Hot 节点)**:Doc Replication,< 100ms 可见,容灾用
- **Async Replica(Warm 节点)**:Segment Replication via Remote,1-2s 可见,弹性查询分流

### 2.6 与 K8s 的集成

- Hot 节点:StatefulSet(带本地 NVMe PV)
- Warm 节点:Deployment + HPA(临时存储,Pod 销毁即清理)
- Cold 节点:Deployment(高密度,小缓存)
- Master/Coord:StatefulSet/Deployment

Warm 扩容生命周期:Pod 启动(5s) → 加入集群(10s) → 读取 Remote metadata(5s) → 接收查询(10s) → FileCache 预热(30s) = 总计 ~60s

Warm 缩容生命周期:PreStop → exclude 节点 → 等待路由切换(10s) → Pod 销毁,FileCache 丢弃

### 2.7 容量规划公式

```
Hot 节点数 = max(ceil(TPS/30K), ceil(hot_data/800GB), 3)
Warm 节点数(基线) = ceil(QPS_p50 / 5K)
Warm 节点数(峰值) = ceil(QPS_peak / 5K)
Cold 节点数 = max(ceil(cold_data / 5TB), 2)
Coord 节点数 = ceil(QPS_peak / 20K)
Master 节点数 = 3(固定)
```

### 2.8 Lean Sync Replica(LSR)

#### 2.8.1 核心思想

Replica 只本地保留"未上传到 Remote 的 tail 数据",历史 segments 从 Remote+FileCache 加载。

- 传统方案 Replica 占用:1TB NVMe
- Lean Replica 占用:~6GB tail + 200GB cache = 206GB SSD
- 综合 TCO 节约:~92%

#### 2.8.2 关键状态变量

```
last_uploaded_seqno (LUS) = Primary 已上传到 Remote 的最大 seqno
replica_local_max_seqno  = Replica 已 ACK 的最大 seqno
Tail 大小 = replica_local_max_seqno - LUS ≈ 30 秒写入量
```

#### 2.8.3 写入路径

1. Client → Primary:Engine.index → 本地 Lucene + Translog
2. Primary → Sync Replica:Doc Replication(全量 doc)→ 写本地
3. Primary → LSR:转发 doc → 写 tail engine(内存级 segment + tail translog)→ ACK
4. Primary refresh → segment upload → ACK → broadcast LUS → LSR drop tail ≤ LUS

#### 2.8.4 故障场景

- **Primary 宕机**:LSR 持有 tail [LUS+1..max],提升为 New Primary,RPO=0,RTO<5s
- **Replica 宕机**:新节点从 Remote 加载 metadata + 从 Primary 拉 tail,恢复 <30s
- **双击穿**:退化到 Remote 恢复(丢失 tail),缓解措施:高频上传(5-10s)

#### 2.8.5 LayeredDirectory

```java
public class LayeredDirectory extends Directory {
    private final Directory tailDirectory;              // 本地 tail segments
    private final RemoteCachedDirectory remoteDirectory; // Remote + FileCache
    private volatile long lastUploadedSeqno;

    public IndexInput openInput(String name, IOContext ctx) {
        if (segmentMaxSeqno(name) > lastUploadedSeqno) {
            return tailDirectory.openInput(name, ctx);  // tail 走本地
        }
        return remoteDirectory.openInput(name, ctx);    // 历史走 Remote
    }
}
```

#### 2.8.6 演进路径

- Phase 1:Doc Replication + Remote Store write-back(简单可靠)
- Phase 2:Lean Sync Replica + LayeredDirectory(降成本)
- Phase 3:Triple-Replica(Sync + LSR + Async)

---

## 第 3 章 存储层 / 写入路径设计

### 3.1 存储层组件

```
Primary Hot Node
├── Engine (InternalEngine)
│   ├── IndexWriter → Lucene Local
│   ├── Translog (本地)
│   └── RefreshListenerChain
│       ├── RemoteStoreRefreshListener  → 异步上传 segments
│       └── TranslogUploadListener      → 异步上传 translog
├── Storage Layer
│   ├── HybridDirectory (本地 NVMe)
│   └── RemoteSegmentStoreDirectory (影子目录)
└── Upload Pipeline
    ├── SegmentUploadQueue (priority queue)
    ├── TranslogUploadQueue (FIFO)
    ├── MetadataUploadService
    └── BackpressureController
```

### 3.2 写入路径全链路

同步路径(决定客户端 ACK):
1. Lucene 内存写入
2. 本地 Translog 写
3. 本地 Translog fsync(durability=request 时)
4. Sync Replica 同步,等 ACK
5. LSR tail 同步,等 ACK
6. → **ACK 客户端**

异步路径(write-back):
7. Translog upload(1-3s)
8. Refresh(1s 默认)
9. Segment upload(refresh 后秒级)
10. Async Replica 可见(Remote 上传 + 拉取)

### 3.3 Translog Remote 化

- 双写模型:本地 Translog(同步,决定 ACK) + Remote Translog(异步,决定 RPO)
- 按 generation 上传,最小延迟 ~1-3s
- 关键配置:upload.interval=1s, batch_size=4MB, parallel_upload=8

Remote Translog 路径布局:
```
s3://bucket/cluster_uuid/index_uuid/<shard_id>/translog/
├── data/      translog-N.tlog + translog-N.ckp
└── metadata/  translog__<primary_term>__<gen>.metadata
```

### 3.4 Segment 上传设计

上传触发时机:
| 触发点 | 策略 | 说明 |
|--------|------|------|
| Refresh | 必上传 | 小 segment |
| Flush | 必上传 | 大 segment + commit |
| Force Merge | 必上传 | merged segment |
| Tiering 转换 | 必上传 | 确保数据在 Remote |
| Replica Promotion | 必上传 | fail-over 关键路径 |

上传调度:
- 优先级:Tiering 强制 > Flush > Refresh > 后台 retry
- 并发:8 流(默认)
- 限流:max_bytes_in_flight = 256MB
- 大文件:≥ 16MB 启用 multipart

Segment Metadata:
```json
{
  "primary_term": 5,
  "generation": 42,
  "checkpoint": 9876,
  "files": { "_3.cfs": {"size": ..., "checksum": "sha256:..."}, ... },
  "replication_checkpoint": { "shard_id": "shard-0", "seq_no": 9876 }
}
```

### 3.5 一致性与持久化保证

ACK 后保证:数据在至少 2 个本地节点磁盘上(Primary + Sync Replica)

单写者语义:
- ClusterState 维护 primary_term 单调递增
- metadata 文件名包含 primary_term
- 新 Primary 必须先递增 primary_term 再上传
- 旧 Primary 上传不会破坏新 Primary 的目录视图

### 3.6 Remote 故障降级

| 级别 | 条件 | 处理 |
|------|------|------|
| 警告 | 间歇慢,本地 <70% | 日志告警,Metric 上报 |
| 背压 | 持续不可用,本地 >70% | Coord 减速(429),优先上传旧数据 |
| 拒写 | 本地 >90% | 索引 read-only,查询不受影响 |

恢复后:自动消化堆积队列(限速) → 解除 read-only → LUS 推进

### 3.7 孤儿文件清理

- 周期:每 24h,低峰期
- 二次校验:文件创建 >24h + 不被任何活跃 metadata 引用
- 软删除:移到 .trash/,保留 7 天后硬删
- 由 Master 统一调度

### 3.8 关键配置项

```yaml
# 索引级别
index.remote_store.enabled: true
index.replication.type: HYBRID           # DOCUMENT / SEGMENT / LEAN_SYNC / HYBRID
index.translog.durability: request
index.translog.remote.upload.interval: 1s
index.refresh_interval: 1s

# 节点级别
node.remote_store.segment.upload.parallelism: 8
node.remote_store.segment.upload.max_bytes_in_flight: 256mb
node.remote_store.backpressure.local_disk_threshold_warn: 0.70
node.remote_store.backpressure.local_disk_threshold_block: 0.90

# 集群级别
cluster.remote_store.cleanup.interval: 24h
cluster.remote_store.cleanup.soft_delete_retention: 7d
cluster.remote_store.single_writer.enabled: true
```

---

## 第 4 章 计算层 / 查询读取路径设计

### 4.1 查询读取路径架构

```
Client → Coordinator Node
  ├── ShardSelector(智能选副本)
  ├── AdaptiveReplicaSelector(基于响应延迟动态选副本)
  └── CrossTierRoutingPolicy(Hot/Warm/Cold 路由策略)
         │
         ├── Hot Data Node:  Local NVMe,延迟 <50ms
         ├── Warm Data Node: FileCache(热点缓存),延迟 50-200ms
         └── Cold Data Node: Small Cache(冷点缓存),延迟 200-1s
                                    │ cache miss
                                    ▼
                              Remote Object Store
```

### 4.2 Coordinator 路由策略

| 规则 | 条件 | 路由目标 |
|------|------|---------|
| 写后即查 | preference=_primary / ?refresh=true | 仅 Primary(Hot) |
| 近实时(默认) | Hot 索引 | 优先 Hot 副本 |
| 大流量分流 | query_hint=eventual_consistency | 允许走 Warm 副本 |
| 跨层查询 | 时间范围命中多层 | 并发 scatter |
| 聚合优先 | query_hint=batch | 倾向 Warm/Cold |

自适应副本选择评分:
```
score = w1*response_time + w2*service_time + w3*queue_size
      + w4*tier_penalty + w5*cache_miss_penalty
```

### 4.3 FileCache 三级层级

| 层级 | 介质 | 内容 | 容量 | 逐出策略 |
|------|------|------|------|---------|
| L1:Hot Files Cache | 堆外内存 | metadata (.si .cfe), posting 起始 block | 4-8GB | LRU |
| L2:SharedBlobCache | SSD | Lucene 数据 block(16MB Region) | Warm:200GB / Cold:50GB | LFU+Decay |
| L3:Remote | 对象存储 | 按需下载,16MB 粒度 | 无限 | N/A |

SharedBlobCache 核心设计:
- 16MB Region 粒度
- SparseFileTracker 字节级追踪(支持部分 fetch、合并 fetch)
- LFU + Decay 逐出(频率衰减,防止历史热点永驻)
- 多请求重叠范围自动合并

### 4.4 IndexInput 读取路径

```java
public class LayeredIndexInput extends IndexInput {
    private final SharedBlobCacheService cache;
    private final BlobContainer remoteBlobs;

    public void readBytes(byte[] b, int offset, int len) {
        // 通过 cache 读取(缓存层处理 hit/miss)
        ByteBuffer data = cache.read(cacheKey, position, len);
        data.get(b, offset, len);
        position += len;
    }
}
```

### 4.5 Prefetch 策略

| 场景 | 策略 | 触发时机 |
|------|------|---------|
| 分片新加载 | 预取 metadata + posting 起始 | allocation 完成 |
| Tiering 流转 | 顺序预取热点 segment | 流转完成 |
| 时间范围查询 | 按时间序预取相邻 segment | 查询命中 |
| 聚合查询 | 预取 doc_values 列 | 查询解析 |

禁用预取:Cold 层、缓存 >80%、网络拥塞

### 4.6 并发控制

分层独立线程池:
- Hot:search size=cores, queue=1000
- Warm:search size=cores×2(IO 密集), queue=500
- Cold:search size=cores×4(高 IO 等待), queue=200
- Throttled:size=4(大查询隔离)

大查询检测:基于 shard 大小、aggregation 类型、深分页、跨层访问等维度估算成本。

### 4.7 跨层查询

时间序场景(90 天范围):
- Hot(4 天):立即返回 <50ms
- Warm(~85 天):缓存命中 80%,100-200ms
- Cold(~90 天):并发拉 Remote,500-1000ms
- 支持 Async Search + 部分结果渐进返回

### 4.8 故障容错

| 故障 | 处理 |
|------|------|
| Warm 节点宕机 | Coord 切到 Hot 副本,HPA 补充 |
| Remote 分区 | Cache hit 照常,miss 返回 partial + warning |
| Cold 节点 OOM | Circuit Breaker 拒绝,Coord 绕过 |
| Cache 损坏 | Checksum 校验,自动重新拉取 |

### 4.9 关键配置项

```yaml
# FileCache
node.filecache.size: 200gb
node.filecache.region_size: 16mb
node.filecache.eviction_policy: LFU_DECAY

# Prefetch
node.prefetch.enabled: true
node.prefetch.rate_limit: 200mb

# 路由
cluster.routing.use_adaptive_replica_selection: true
cluster.search.cross_tier.partial_result: true
cluster.search.cross_tier.cold_timeout: 30s
```

---

## 第 5 章 TieringService 状态机与生命周期

### 5.1 索引层级状态机

```
CREATING → HOT → HOT_TO_WARM → WARM → WARM_TO_COLD → COLD → ARCHIVED
                     ↑ rollback    ↑        ↑ rollback
                     └─────────────┘        └────────────────────────┘
           promote_to_hot(反向): COLD → WARM → HOT
```

- HOT / WARM / COLD:稳态
- HOT_TO_WARM / WARM_TO_COLD:过渡态(带 rollback 能力)
- ARCHIVED:终态(snapshot,需手动 restore)

### 5.2 状态转换表

| From | To | Trigger | 关键动作 |
|------|-----|---------|---------|
| HOT | HOT_TO_WARM | ILM(age>7d 等) | flush + Remote sync + read-only + 清零副本 + 改路由 |
| HOT_TO_WARM | WARM | Warm 副本就绪 | 删 Hot 本地副本,释放 NVMe |
| HOT_TO_WARM | HOT | rollback | 恢复路由,取消 read-only |
| WARM | WARM_TO_COLD | ILM(age>30d) | 改路由,缩减缓存策略 |
| WARM_TO_COLD | COLD | Cold 节点就绪 | 同上 |
| WARM | HOT(反向) | promote_to_hot | 增副本,改路由到 Hot,启用写入 |
| COLD | ARCHIVED | 手动 archive | 创建 snapshot,删除索引 |

### 5.3 流转执行 — Hot → Warm

**Phase 1:准备**
1. 标记 HOT_TO_WARM + read-only
2. 强制 refresh + flush
3. 等待 Remote Store 上传完成
4. 校验 Remote 完整性

**Phase 2:路由切换(原子 ClusterState 更新)**
1. 修改 tier preference → data_warm
2. 副本数清零
3. 写入 TieringMetadata

**Phase 3:等待 Warm 副本就绪**
1. AllocationService 触发 Warm 节点拉取 metadata
2. Warm 节点初始化 RemoteSegmentDirectory(无需下载文件)
3. 开始接受查询

**Phase 4:清理 Hot**
1. Hot 节点删除本地分片(自动)
2. 释放 NVMe 空间

### 5.4 ILM 集成

```yaml
PUT _ilm/policy/logs-policy
{
  "policy": {
    "phases": {
      "hot": { "actions": { "rollover": { "max_age": "1d" } } },
      "warm": { "min_age": "7d", "actions": { "tier": { "target_tier": "warm" } } },
      "cold": { "min_age": "30d", "actions": { "tier": { "target_tier": "cold" } } },
      "delete": { "min_age": "90d", "actions": { "delete": {} } }
    }
  }
}
```

自定义 `TierAction` 作为 LifecycleAction 嵌入 ILM 框架。

### 5.5 服务连续性

| 阶段 | 写入 | 查询 | 备注 |
|------|------|------|------|
| HOT | 正常 | 正常 | |
| HOT_TO_WARM | 拒绝(短暂 <30s) | 正常(Hot 路由) | |
| WARM | 默认拒绝 | Warm 节点 | |
| WARM_TO_HOT | 短暂拒绝 | 旧副本继续 | 切换期 <1min |
| COLD | 拒绝 | Cold 节点 | 延迟较高 |

关键:Hot→Warm 时,Hot 副本保留到 Warm 就绪后再删。

### 5.6 故障与回滚

| 失败场景 | 处理 |
|---------|------|
| Remote 同步超时 | 回滚到 HOT,告警,指数退避重试 |
| Warm 容量不足 | 触发 AutoScaler 扩容,等 5min,仍失败回滚 |
| ClusterState 提交失败 | 新 Master 扫描 transitioning 索引,判断完整性 |
| 索引被删除 | 取消流转,标记 CANCELLED |
| 回滚本身失败 | STUCK 状态,暴露 _tiering/recovery API |

### 5.7 诊断 API

```
GET _tiering/status                    # 所有索引层级状态
GET _tiering/<index>/status            # 单索引详细
POST _tiering/<index>/promote          # 手动 promote
POST _tiering/<index>/tier             # 手动流转
POST _tiering/<index>/rollback         # 手动回滚
POST _tiering/<index>/recovery         # STUCK 修复
```

### 5.8 关键配置项

```yaml
cluster.tiering.enabled: true
cluster.tiering.concurrent_transitions.max: 5
cluster.tiering.transition.timeout: 30m
cluster.tiering.failure.retry_max: 3
cluster.tiering.failure.retry_backoff: 5m
cluster.tiering.stuck_threshold: 2h

index.tiering.promote.enabled: true
index.tiering.promote.qps_threshold: 1000
index.tiering.promote.cooldown: 24h
```

---

## 第 6 章 弹性伸缩与 Autoscaler

### 6.1 Autoscaler 架构

```
Metric Collector (per-node/shard stats, 10s interval)
       │
       ▼
Decider Pipeline (并行):
  ├── ReactiveStorageDecider   (磁盘水位反应)
  ├── ProactiveStorageDecider  (写入趋势预测)
  ├── LatencyDecider           (P95/P99 反应)
  ├── QueueDecider             (线程池积压)
  ├── ComputeDecider           (CPU/Mem 反应)
  └── PredictiveDecider        (时序模式预测)
       │
       ▼
Policy Aggregator:
  ├── 扩容:取所有 Decider 最大需求(any-up)
  ├── 缩容:取所有 Decider 最小需求(all-down)
  ├── Cooldown / RateLimit
  └── 边界检查(min/max)
       │
       ▼
Action Dispatcher → K8s API / HPA / ECK
```

### 6.2 Decider 详细设计

**ReactiveStorageDecider**(仅 Hot):
- 60% 开始提示,75% 扩容,85% 紧急扩容
- 目标:回到 60% 水位

**ProactiveStorageDecider**(仅 Hot):
- 计算最近 1h 写入速率,预测未来 2h 增长
- 提前扩容

**LatencyDecider**(Warm/Coord):
- P99 > scaleUpThreshold → 扩容
- P99 < scaleDownThreshold AND P95 < threshold×0.8 → 缩容

**QueueDecider**(Warm/Coord):
- 有 rejection → 立即扩容
- 队列 > threshold → 按溢出比例扩容

**PredictiveDecider**(Warm/Coord):
- 7 天滑动平均 + 同比增长预测
- PromotionRegistry 促销覆盖

### 6.3 分层伸缩策略

| 层级 | Decider | Min | Max | Cooldown(up/down) | Rate(up/down) |
|------|---------|-----|-----|-------------------|---------------|
| Master | 不伸缩 | 3 | 3 | N/A | N/A |
| Coord | Latency+Queue | 2 | 20 | 60s/5m | +50%/-20% |
| Hot | Storage+Compute | 3 | 30 | 5m/30m | +33%/-10% |
| Warm | Latency+Queue+Predictive | 2 | 100 | 30s/5m | +100%/-30% |
| Cold | Storage | 2 | 20 | 1h/6h | +50%/-20% |

### 6.4 K8s 集成

- Custom Metrics API:ES metric → Prometheus → prometheus-adapter → HPA
- ECK Operator:NodeSet 管理,Autoscaler 推荐 → ECK 调整 count
- Warm HPA:基于 P99 延迟 + 队列长度,scaleUp 30s stabilization,scaleDown 300s

### 6.5 缩容安全机制

**Hot 节点 PreStop**:
1. 排空节点(allocation.exclude)
2. 等待分片迁移(最多 10min)
3. 触发 Remote Store 最终上传
4. 确认无未完成上传

**Warm/Cold 节点 PreStop**:
1. 从分配中排除(切流)
2. 等待 15s(路由切换)
3. Pod 销毁(数据在 Remote,无需等)

缩容防护规则:
- 不破坏副本可用性(每个 shard 至少 1 active replica)
- Master 不缩
- 各层 min_replicas 严格保护
- Hot 缩容仅在安全时段(02:00-06:00)
- Tiering 流转中禁止缩容相关层级

### 6.6 大促场景编排

```
T-7天: 预热期(运营录入 PromotionRegistry)
T-1天: 预扩容(Predictive + 手动覆盖)
T-1小时: 全量预扩(Warm×5, Coord×3, lock scale_down)
T+0: 大促开始(实时弹性,不受 cooldown 限制)
T+12h: 逐步缩容(解锁 scale_down)
T+24h: 回归基线
```

PromotionRegistry API:
```
PUT _autoscaling/promotion/<id>
{ "start_time", "end_time", "scale_factors": { "warm": 5.0 }, "lock_scale_down": true }
```

### 6.7 Autoscaling API

```
GET _autoscaling/capacity              # 查看当前/目标容量
POST _autoscaling/capacity/_evaluate   # 手动触发评估
PUT _autoscaling/policy/<tier>_hpa     # 设置策略
```

### 6.8 关键配置项

```yaml
cluster.autoscaling.enabled: true
cluster.autoscaling.evaluation_interval: 30s
cluster.autoscaling.cooldown.up: 30s
cluster.autoscaling.cooldown.down: 5m
cluster.autoscaling.rate.up: 1.0
cluster.autoscaling.rate.down: 0.3
cluster.autoscaling.deciders.latency.target_p99: 200ms
cluster.autoscaling.deciders.queue.threshold: 100
cluster.autoscaling.deciders.predictive.lookahead: 15m
```

---

## 第 7 章 容错 / 运维 / 可观测性

### 7.1 故障域与容错矩阵

| 故障域 | 影响范围 | 检测时延 | 自愈时延 |
|--------|---------|---------|---------|
| 单 Pod 故障 | 1 节点 | 5s | 30s |
| 单节点磁盘故障 | 1 节点 + 数据 | 即时 | 1-5min |
| 单 AZ 故障 | 1/3 集群 | 30s | 1min |
| Remote 区域故障 | 写入降级 | 1min | 5min(降级) |
| Master 选举失败 | 集群不可写 | 30s | 1min |

### 7.2 各角色容错策略

| 角色 | 单节点故障 | 单 AZ 故障 | Remote 故障 |
|------|-----------|-----------|-----------|
| Master | 剩 2 节点 quorum,自动选主 | 跨 3 AZ quorum 存活 | 不影响 |
| Coord | 客户端 LB 切换 | 同左 | 不影响 |
| Hot | Sync Replica 自动提升,RTO<5s | 跨 AZ 副本仍存活 | 写入背压+降级 |
| Warm | Async Replica 切流+HPA 补,RTO<60s | 同左 | Cache hit 继续 |
| Cold | 同 Warm | 同左 | 同 Warm,容忍度更高 |

### 7.3 自动恢复机制

**副本自愈(Lean Sync Recovery)**:
1. Master 检测节点离开(5s)
2. AllocationService 分配新节点(5s)
3. 从 Remote 加载 metadata + 从 Primary 拉 tail(10-15s)
4. 副本 STARTED,加入复制组(5s)
5. 总计 ~30s(vs 传统方案分钟级)

**Primary 提升(LSR → New Primary)**:
1. Master 检测 Primary 失联(5s)
2. 选择 LSR 提升,PrimaryTerm++(5s)
3. 提升 tail segments 为正式(5s)
4. 获取 Remote 单写者锁(5s)
5. 开始接受写入(5s)
6. RPO=0, RTO<30s

**单写者锁**:
- Compare-And-Swap 语义:读取 currentTerm,如 < newTerm 则写入
- Primary 每 10s 续约
- 旧 Primary 复活后加锁失败

### 7.4 灾备方案

**跨 Region 容灾(三种选型)**:
1. S3 Cross-Region Replication:简单,RPO 1-5min
2. CCR 增强(基于 Remote metadata):RPO<30s
3. 双活双写:RPO=0,延迟翻倍,仅关键索引

**PITR(Point-In-Time Recovery)**:
- Remote Store 天然支持:metadata 按 generation 保留
- 保留策略:24h 全量 → 7d 每小时 → 30d 每天
- 恢复 API:`POST /index/_pitr_restore { "timestamp": "..." }`

### 7.5 升级与发布

滚动升级顺序:Master → Coord → Cold → Warm → Hot(越后越保守)

Hot 升级:
1. Disable allocation
2. 节点排空 + Remote sync 完成
3. K8s 删除 Pod(触发 PreStop)
4. 新 Pod 启动,AllocationService 恢复分片

### 7.6 可观测性 — Metrics

关键 SLI:
```yaml
# 写入
es_indexing_latency_seconds{quantile="0.99"}
es_indexing_throughput
es_indexing_failures_total{reason}

# 查询
es_search_latency_seconds{quantile="0.99",tier}
es_search_throughput{tier}

# Remote Store
es_remote_store_upload_latency{quantile}
es_remote_store_lag_seconds

# FileCache
es_filecache_hit_ratio{tier}
es_filecache_evictions_total{tier}

# Tiering
es_tiering_in_progress{from,to}
es_tiering_duration_seconds{from,to}
es_tiering_stuck_indices

# Autoscaling
es_autoscaling_current_nodes{tier}
es_autoscaling_target_nodes{tier}
```

SLO 定义:
| SLO | 目标 |
|-----|------|
| Write Availability | 99.95% |
| Search Availability | 99.99% |
| Write P99 | <100ms |
| Search P99 Hot | <50ms |
| Search P99 Warm | <200ms |
| Search P99 Cold | <1000ms |
| RPO Remote Store | <5s |
| Scale-up Warm P95 | <60s |

### 7.7 可观测性 — Logs & Traces

日志:JSON 结构化,含 trace_id/span_id,分类(application/slow_search/slow_indexing/audit/gc)

Traces:OpenTelemetry 全链路,写入路径可追到 S3 PutObject,采样 10%,关键操作强制采样

### 7.8 告警规则

| 级别 | 告警 | 条件 |
|------|------|------|
| P0(5min 响应) | ClusterRed | status=red 持续 1m |
| P0 | WriteAvailabilityBreach | <99.9% 持续 5m |
| P0 | RemoteStoreDown | 上传失败率 >1/5m 持续 2m |
| P1(30min 响应) | HighWriteLatency | P99>100ms 持续 10m |
| P1 | TieringStuck | stuck_indices>0 持续 30m |
| P1 | HotDiskHigh | usage>85% 持续 5m |
| P2(2h 响应) | AutoscalingFlapping | decisions>10/h |
| P2 | LowCacheHitRatio | hit_ratio<70% 持续 30m |

### 7.9 混沌工程

| 类型 | 频率 | 工具 | 验收 |
|------|------|------|------|
| Pod 随机 kill | 每天 | Chaos Mesh | SLO 不破 |
| AZ 整体故障 | 每月 | 网络分区 | 自愈 <1min |
| Remote 故障 | 每月 | Toxiproxy | 写入降级正常 |
| 磁盘满 | 每月 | 脚本 | 触发背压 |

### 7.10 关键配置项

```yaml
# 故障检测
cluster.fault_detection.leader_check.interval: 1s
cluster.fault_detection.leader_check.retry_count: 3

# Remote Store 心跳
cluster.remote_store.heartbeat.interval: 30s
cluster.remote_store.single_writer.heartbeat_interval: 10s

# 追踪
tracing.exporter.type: otlp
tracing.sampler.fraction: 0.1

# 慢日志
index.search.slowlog.threshold.query.warn: 1s
index.indexing.slowlog.threshold.index.warn: 500ms
```

---

## 第 8 章 多阶段演进路线

### 8.1 四阶段总览

| Phase | 时间 | 目标 | 关键交付 |
|-------|------|------|---------|
| Phase 1 | M0-M3 | 基础存算分离 | Remote Store write-back, Doc Replication, Hot+Remote |
| Phase 2 | M3-M6 | 分层架构 | Warm/Cold+FileCache, LSR, TieringService, ILM |
| Phase 3 | M6-M9 | 智能弹性 | Autoscaler, K8s/ECK, promote_to_hot, 大促编排 |
| Phase 4 | M9-M12 | 极致优化 | 跨 Region DR, PITR, Prefetch, OTel, 混沌工程 |

### 8.2 Phase 1:基础存算分离(M0-M3)

**目标**:跑通 Remote Store 写入路径,沿用 Doc Replication,验证 RPO=0/RTO<30s

**任务**:
- M0(2周):调研+环境搭建+benchmark
- M1(4周):RemoteSegmentStoreDirectory + RefreshListener + 并发上传 + metadata
- M2(4周):Remote Translog + 单写者锁 + Primary 提升 + 故障降级
- M3(2周):压测(30K TPS/10K QPS)+ 故障注入 + 文档

**验收**:
- 写入吞吐 ≥ 同等配置 90%
- P99 ≤ 100ms
- 7×24h 压测无 OOM/数据丢失
- 100 次故障注入自愈 100%

### 8.3 Phase 2:分层架构(M3-M6)

**目标**:引入 Warm/Cold,LSR 降低 Hot 成本 85%,TieringService 自动流转

**任务**:
- M3-M4(4周):SharedBlobCache + SparseFileTracker + LayeredIndexInput + LFU
- M4-M5(4周):LayeredDirectory + TailDirectory + LeanSyncReplicaEngine + LUS broadcast
- M5-M6(4周):TieringService 状态机 + HotToWarm/WarmToCold + ILM 集成

**验收**:
- ILM 自动 Hot→Warm→Cold
- 流转期间查询不中断
- LSR 存储 ≤ 全量 15%
- Warm P99 ≤ 200ms(cache hit 80%+)
- TCO 较纯 Hot 降 60%+

### 8.4 Phase 3:智能弹性(M6-M9)

**目标**:Autoscaler 6 类 Decider,Warm 弹性 <60s,大促编排

**任务**:
- M6-M7(4周):AutoscalingService + 6 Deciders + PolicyAggregator
- M7-M8(4周):Custom Metrics API + HPA + ECK + PreStop Hook
- M8-M9(4周):WarmToHotTransitioner + CrossTierRouting + PredictiveDecider + PromotionRegistry

**验收**:
- Warm 60s 内自动扩容
- 流量回落 5min 逐步缩(不抖动)
- 大促模拟 PromotionRegistry 预扩成功
- 反向流转 5min 完成

### 8.5 Phase 4:极致优化(M9-M12)

**目标**:跨 Region DR,PITR,Prefetch,全链路 Trace,生产化

**任务**:
- M9-M10(4周):CCR 增强 + S3 跨区复制 + DR 切换 + PITR API
- M10-M11(4周):PrefetchService + Async Search 优化 + 大查询隔离
- M11-M12(4周):OTel 接入 + Grafana Dashboard(L1-L4) + 告警 + Chaos Mesh + Helm Chart

**验收**:
- DR 切换 RTO ≤ 5min
- PITR 24h 内任意时间点可恢复
- Prefetch 提升 cache hit 10%+
- 月度混沌演练全部通过
- 7×24h 无人工干预运行 30 天

### 8.6 里程碑

| 时间 | 里程碑 |
|------|--------|
| Week 0 | 项目启动,设计评审通过 |
| Week 6 | Remote Segment Store 跑通 |
| Week 12 | Phase 1 GA |
| Week 18 | FileCache + LSR 可用 |
| Week 26 | Phase 2 GA |
| Week 30 | Autoscaler 上线 |
| Week 38 | Phase 3 GA |
| Week 42 | 跨 Region DR 可用 |
| Week 52 | Phase 4 GA(全功能) |

### 8.7 团队配置

核心研发 6-8 人:
- 1× 架构师,2× 存储引擎,2× 分布式系统,1× K8s/Cloud,1× 性能优化,1× 测试

辅助:1× SRE,0.5× 文档,0.5× 产品

### 8.8 风险登记册

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Remote Store 性能不达标 | 中 | 高 | 提前 PoC 多个对象存储 |
| LSR 复杂度引入 bug | 高 | 高 | Phase 1 先跑基础,Phase 2 再引入 |
| Autoscaler 抖动 | 中 | 中 | Cooldown + RateLimit 双保险 |
| 与 ES 7.17.4 兼容性 | 低 | 高 | 保持 Directory 抽象,setting 灰度 |
| K8s/ECK 集成复杂度 | 中 | 中 | 早期投入专职 K8s 工程师 |
| 团队人力不足 | 中 | 高 | 阶段性外援 |
| 上游 OpenSearch 演进过快 | 中 | 中 | 每月 sync,关键差异提前规划 |

### 8.9 交付物清单

- 代码:ES 7.17.4 fork + Helm Chart + ECK 扩展 + Migration Tool
- 文档:设计文档 + API Reference + 运维手册 + Runbook + Migration Guide + Performance Guide
- 工具:Grafana Dashboard + Alerting Rules + Chaos Mesh 脚本
- 生态:官方文档站 + Demo 环境
