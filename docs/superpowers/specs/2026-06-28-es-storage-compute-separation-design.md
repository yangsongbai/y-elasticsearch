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
| 弹性伸缩 | 分钟级(Warm/Cold 零搬迁,Hot 预扩) | 分钟级 | 手动 | 手动 |
| 层级流转 | 状态机 + TieringPolicy | 手动 + ILM | Snapshot mount | allocate action |

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
- RegionSparseFileTracker 字节级追踪(支持部分 fetch、合并 fetch)
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
| HOT | HOT_TO_WARM | TieringPolicy(age>7d 等) | flush + Remote sync + read-only + 清零副本 + 改路由 |
| HOT_TO_WARM | WARM | Warm 副本就绪 | 删 Hot 本地副本,释放 NVMe |
| HOT_TO_WARM | HOT | rollback | 恢复路由,取消 read-only |
| WARM | WARM_TO_COLD | TieringPolicy(age>30d) | 改路由,缩减缓存策略 |
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

### 5.4 TieringPolicy 集成(独立于 x-pack ILM)

通过 index settings 声明流转策略,由 `TieringPolicyService` 周期性评估触发:

```yaml
PUT logs-2024/_settings
{
  "index.tiering.warm_after": "7d",
  "index.tiering.cold_after": "30d",
  "index.tiering.delete_after": "90d"
}
```

或通过 index template 统一配置:

```yaml
PUT _index_template/logs-template
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": {
      "index.tiering.warm_after": "7d",
      "index.tiering.cold_after": "30d"
    }
  }
}
```

`TieringPolicyService` 作为 `ClusterStateListener` + 定时调度(默认 5min 间隔),遍历所有启用 tiering 的索引,比较 `index.creation_date` + 配置的 age 条件,满足时调用 `TieringService.transitionIndex()`。全部实现在 server 模块中,**不依赖 x-pack ILM**。

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

### 6.9 弹性伸缩可行性分析:分钟级扩容与数据搬迁

#### 6.9.1 各层级弹扩能力评估

| 层级 | 分钟级可行? | 数据搬迁量 | 扩容路径 |
|------|-----------|-----------|---------|
| Warm | **是** | 接近 0(仅 metadata KB 级) | Pod→加入集群→读metadata→接收查询→cache预热 ≈60s |
| Cold | **是** | 0(纯 Remote 读取) | Pod→加入集群→接收查询 ≈30s |
| Coord | **是** | 0(无状态) | Pod→加入集群→路由就绪 ≈20s |
| Hot | **否** | TB 级(Primary shard 数据) | shard rebalance 需分钟到数十分钟 |

#### 6.9.2 Warm 层弹扩零搬迁原理

```
扩容前:  Warm-1(300 shards) + Warm-2(300 shards) → Remote Store
                                                      ↑ 数据全量在此

扩容后:  Warm-1(200) + Warm-2(200) + Warm-3(200) → Remote Store
                                                      ↑ 新节点只读 metadata,不搬文件
```

Warm/Cold 节点的数据路径:
1. AllocationService 分配 shard → 节点收到 `StartRecovery` 
2. 初始化 `RemoteSegmentStoreDirectory`(下载 segment metadata,~KB)
3. 映射到 `SharedBlobCache`(不预下载 segment 文件)
4. 开始接收查询 → cache miss 时按需拉取 16MB region

**关键:不需要完整下载 segment 文件,按需 region 粒度拉取即可服务**

#### 6.9.3 扩容期间性能降级与缓解

| 问题 | 影响 | 缓解措施 |
|------|------|---------|
| Cache-cold 期(新节点 cache 为空) | 前 30-60s 查询延迟 200-500ms | 渐进式流量切入(权重从 0 线性升到 1) |
| Remote 读取风暴(大量节点同时扩) | Remote 带宽压力,其他节点 cache miss 延迟升高 | 限制并发 prefetch 带宽(200MB/s/node) |
| ClusterState 分发延迟(万级 shard) | AllocationDecision 计算耗时 | 批量分配(每轮最多 50 个 shard 迁移) |
| Adaptive Replica Selection 滞后 | 新节点未被选中 | 初始 bias:新节点强制接收 10% 流量以快速预热 |

#### 6.9.4 Hot 层弹扩的根本困难

Hot 节点持有 Primary shard,扩容意味着 shard rebalance:

```
Hot-1(P0,P1,P2) + Hot-2(P3,P4,P5) → 扩 Hot-3

需要将 P2,P5 迁移到 Hot-3:
  P2: 800GB segment 数据 → 即使走 Remote 恢复也需下载 800GB
  网络 25Gbps 理论极限: 800GB / 25Gbps ≈ 4.3min(不含开销)
  实际考虑限速+并发: 10-30min
```

**Hot 弹扩无法避免数据搬迁,因此设计上应规避频繁 Hot 扩容。**

#### 6.9.5 规避 Hot 扩容的设计策略

| 策略 | 原理 | 适用场景 |
|------|------|---------|
| **写入弹性由 Coord 吸收** | 短期 burst 在 Coord 层 buffer(bulk queue 扩容),不触发 Hot 扩 | 流量突峰 <10min |
| **预分片(Over-sharding)** | 创建索引时多分 shard,每个 shard 更小,rebalance 更快 | 可预见增长场景 |
| **时间序索引滚动** | 新索引分配到新 Hot 节点,旧索引不动 | 日志/时序场景 |
| **Hot 预扩(大促)** | 通过 PromotionRegistry 提前扩容,而非实时弹性 | 可预见流量高峰 |
| **写入限流+背压** | 接近容量上限时返回 429,客户端重试 | 防止 OOM,争取扩容时间 |

#### 6.9.6 推荐弹扩架构

```
                    ┌─────────── 分钟级弹扩(零搬迁) ──────────────┐
                    │                                              │
 写入(Hot)          │  查询(Warm/Cold/Coord)                      │
 ┌──────────┐      │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
 │ Hot 固定  │      │  │ Warm HPA │  │ Cold HPA │  │Coord HPA │  │
 │ 预分配容量│      │  │ 30s扩容  │  │ 30s扩容  │  │ 20s扩容  │  │
 │ 不频繁扩  │      │  │ 零搬迁   │  │ 零搬迁   │  │ 零搬迁   │  │
 └──────────┘      │  └──────────┘  └──────────┘  └──────────┘  │
       │            │       │              │              │        │
       ▼            │       ▼              ▼              ▼        │
 Remote Store ◄─────┼───────┴──────────────┴──────────────┘        │
 (共享存储)         └──────────────────────────────────────────────┘
```

**总结:存算分离的核心收益是查询层(Warm/Cold/Coord)的分钟级零搬迁弹扩。写入层(Hot)通过预扩+滚动索引+限流策略规避实时弹性需求。**

#### 6.9.7 弹扩相关配置项

```yaml
# Warm 扩容控制
cluster.autoscaling.warm.gradual_traffic_ramp: true          # 渐进式流量切入
cluster.autoscaling.warm.ramp_duration: 30s                  # 预热期时长
cluster.autoscaling.warm.prefetch_bandwidth_per_node: 200mb  # 预热带宽限制

# Hot 扩容防护
cluster.autoscaling.hot.rebalance_max_shards_per_round: 50   # 批量迁移上限
cluster.autoscaling.hot.min_shard_move_interval: 30m         # 避免频繁 rebalance
cluster.autoscaling.hot.prefer_new_index_allocation: true    # 新索引优先新节点

# Hot Standby Pool
cluster.autoscaling.warm.hot_standby_nodes: 2                # 预留空闲 Warm 节点
cluster.autoscaling.warm.hot_standby_min_idle: 5m            # 空闲多久回收
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

#### 7.3.1 主从切换风险分析

| 风险场景 | 严重度 | 描述 |
|---------|--------|------|
| 脑裂窗口 | 高 | 网络分区时旧 Primary 和新 Primary 可能同时存在。CAS 单写者锁防止 Remote 数据损坏,但客户端可能短暂读到旧 Primary 过期数据 |
| 双击穿(Primary+LSR 同时故障) | 高 | 退化到 Remote 恢复,丢失 tail 数据(LUS 到 max 之间),RPO 取决于上传频率(5-10s 数据量) |
| Tiering 流转中切主 | 中 | 索引处于 HOT_TO_WARM 过渡态且 read-only 时,新 Primary 需判断继续流转还是 rollback,状态机决策复杂 |
| Remote 故障时切主 | 中 | 新 Primary 无法获取单写者锁(Remote 不可达),写入被阻塞,切换无法完成 |
| LSR tail 膨胀 | 中 | Remote 上传积压时 tail 可能远超预期(几十 GB vs 正常几 GB),提升时间变长 |

#### 7.3.2 主从切换防护措施

**1. Fencing Token(防脑裂读)**

Primary 提升后广播新 primary_term 给所有 Coordinator 节点,Coord 收到后拒绝来自旧 term 的查询响应:

```
New Primary 提升 → PrimaryTerm++ → broadcast(term=N) → Coord 更新本地 term
旧 Primary 返回响应(term=N-1) → Coord 丢弃 → 客户端无感知
```

**2. Remote 降级模式下的切主**

Remote 不可达时允许新 Primary 在本地模式运行:
- 跳过 Remote 单写者锁获取,改为本地 fencing(基于 ClusterState primary_term)
- 写入持续,标记 `remote_sync_pending=true`
- Remote 恢复后异步消化积压 + 获取锁 + 推进 LUS
- 降级期间 Async Replica(Warm)不可见新数据,仅 Hot 副本可见

**3. Tail 膨胀熔断**

```yaml
# 新增配置项
index.remote_store.tail.max_size: 10gb          # tail 超限触发写入限流
index.remote_store.tail.stall_threshold: 20gb   # tail 超限拒绝写入
```

逻辑:
- tail < max_size:正常写入
- max_size ≤ tail < stall_threshold:限流(降低 refresh 频率,触发强制上传)
- tail ≥ stall_threshold:Primary stall 写入,Coord 返回 429,直到上传追上

**4. 流转中切主协议**

在 `TieringMetadata` 中记录流转检查点:

```java
public class TieringMetadata {
    TieringState state;            // HOT_TO_WARM
    int completedPhase;            // 0=准备,1=路由切换,2=等待就绪,3=清理
    long transitionStartTime;
    String initiatingNodeId;
}
```

新 Primary 决策:
- `completedPhase < 1`(未做路由切换):直接 rollback 到 HOT
- `completedPhase == 1`(已切路由):检查 Warm 节点是否就绪,就绪则继续,否则等待
- `completedPhase >= 2`:继续清理

**5. 双击穿缓解**

- 默认高频上传(5s interval)控制 tail 窗口
- 可选 `index.remote_store.upload.eager_mode: true`:每次 refresh 后立即上传,将 tail 压缩到 <1s
- 关键索引启用 triple-replica(Sync + LSR + LSR-cross-AZ),双击穿概率降至 10^-6

#### 7.3.3 主分片 Relocation 脏写与丢数据风险分析

Primary Shard Relocation（计划性迁移,如 rebalance、节点缩容排空）与崩溃切主(§7.3.1)风险模型不同:

**Relocation 两阶段:**
1. Recovery 阶段:Target 从 Remote 加载 metadata + 从 Source 同步 tail
2. Handoff 阶段:Source 停写 → 最后一次 sync → Target 提升为新 Primary

**风险场景:**

| 风险 | 严重度 | 描述 |
|------|--------|------|
| Handoff 间隙 tail 丢失 | **高** | Source 停写后,tail 中有已 ACK 但未上传 Remote 的数据需传输给 Target。若 Source 在传输中崩溃,这部分数据丢失 |
| 单写者锁交接窗口 | 中 | Source 释放 CAS 锁 → Target 获取锁之间,若另一节点(旧 Primary 网络恢复)抢锁,可能产生双写竞争 |
| Client 路由滞后脏写 | 中 | ClusterState 传播延迟,Client/Coord 可能仍将写请求路由到已卸任的 Source,Source 写入本地但不再上传 Remote |
| In-flight upload 与新 term 冲突 | 低 | Source handoff 前有 in-flight segment upload(旧 term),handoff 后 Target 用新 term 上传。旧 term 文件不被引用 → 孤儿文件(不丢数据) |

**核心风险详解 — Tail 丢失:**

```
时间线:
  T1: Source 停止接受新写入
  T2: Source flush 本地 tail 到 Target (peer recovery final sync)
  T3: Source 在 T2 期间崩溃
  
结果: Target 提升但缺少 [LUS, T1] 之间的数据 → 已 ACK 数据丢失
```

存算分离架构下此风险更突出:
- LSR 只持有 tail(不是完整本地副本)
- Relocation target 在 recovery 阶段从 Remote 拉 metadata,tail 需从 Source 同步
- Source 崩溃时 tail 唯一来源消失,LSR 此时已不一定持有与 Source 一致的 tail

**防护措施:**

**1. Relocation 前强制上传 tail(ForceUpload-Before-Handoff)**

```
Relocation 触发 → Source 执行 forceUpload(tail) → 等待 LUS 追平 localCheckpoint → 再开始 handoff
```

tail 上传完毕后 handoff 时 Source 的 tail 为空(或仅含 forceUpload 之后极少量新写入),即使 Source 崩溃,Target 从 Remote 即可获取完整数据。

**2. Handoff 原子协议**

```
步骤                                    失败回退
────────────────────────────────────────────────────────────
1. Source: flush + forceUpload           ─→ 重试上传
   确保 tail=0 或 tail≤1个 refresh 周期
2. Source: 停写,记录 lastSeqNo         ─→ N/A
3. Target: 从 Remote 加载最新 metadata   ─→ 回滚(Source 恢复写入)
   确认 seq_no == lastSeqNo
4. Target: CAS 获取单写者锁(term=N+1)  ─→ 回滚(Source 恢复写入)
5. Master: 更新 ClusterState             ─→ 回滚
   Target 为新 Primary
6. Target: 开始接受写入                  ─→ 正常
```

任何步骤失败 → Source 重新激活、恢复写入,不丢数据(步骤 1 已保证 tail 上传完毕)。

**3. Client 路由滞后防护**

- Target 提升后,旧 Source 收到写请求时检查本地 primary_term < ClusterState term → reject(返回 `VersionConflictEngineException`)
- Coordinator 收到 reject 后重试路由到新 Primary(标准 retry 机制)
- 最终一致窗口 ≤ ClusterState 传播延迟(通常 <2s)

**4. 孤儿文件兜底**

In-flight upload 产生的旧 term 孤儿文件由 §3.7 孤儿文件清理机制处理(24h 周期 + 二次校验)。

**新增配置项:**

```yaml
# Relocation 安全控制
cluster.routing.allocation.relocation.force_upload_before_handoff: true   # 默认开启
index.remote_store.relocation.handoff_timeout: 60s                       # forceUpload 超时
index.remote_store.relocation.max_tail_at_handoff: 256mb                 # 超限则延迟 handoff
```

**结论:**
- **脏写**: primary_term + CAS 机制防止 Remote Store 级别脏写;Client 路由滞后写到旧 Source 的数据不会上传 Remote(锁已释放),等效丢失但通过 reject+retry 机制解决
- **丢数据**: 核心风险是 tail 在 handoff 期间仅存于 Source 本地。**ForceUpload-Before-Handoff 将风险窗口压缩到 ≤1 个 refresh 周期(1s)**,配合 handoff 原子协议,任何异常均可安全回退

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

## 第 8 章 风险评审与改进建议

### 8.1 数据一致性与持久性风险

| # | 风险 | 严重度 | 关联章节 | 分析 |
|---|------|--------|---------|------|
| D-1 | **LUS 推进与 Replica 可见性时序不一致** | 高 | §2.8.4 | LUS 推进后 LSR drop tail ≤ LUS，但若 LSR 先 drop 再被查询，而 Remote FileCache 尚未缓存该 segment，会出现短暂"数据黑洞"（本地已删、Remote 未缓存） |
| D-2 | **Write-Back 异步上传的 ACK 语义 — 单 AZ 故障丢数据** | 高 | §3.2, §7.1 | ACK 保证"数据在 2 个本地节点磁盘"。若 Primary + Sync Replica 在同 AZ，整个 AZ 故障时 Remote 尚未上传的 tail 数据丢失。实际 RPO = tail 大小（非声称的 <5s） |
| D-3 | **Translog 与 Segment 上传顺序无保证** | 中 | §3.3-3.4 | Translog upload interval=1s，Segment 在 refresh 后上传。若 segment 先于 translog 上传，Recovery 时从 Remote 恢复可能看到 segment 但缺少对应 translog（seq_no gap） |
| D-4 | **孤儿文件清理误删活跃数据** | 中 | §3.7 | "文件创建 >24h + 不被任何活跃 metadata 引用"判断有 TOCTOU 风险：检查时未被引用，但新 Primary 正在构建引用该文件的 metadata |
| D-5 | **降级模式长时间运行的恢复问题** | 高 | §3.6, §7.3.2 | Remote 降级模式下本地持续写入。降级持续 1h+ 时本地磁盘可能 >90% 触发拒写，且恢复后消化巨量积压的优先级和限速策略未定义 |

### 8.2 可用性风险

| # | 风险 | 严重度 | 关联章节 | 分析 |
|---|------|--------|---------|------|
| A-1 | **Warm 节点 Cache-Miss 雪崩** | 高 | §4.3, §6.9.3 | 多个 Warm 节点同时扩容或重启后 cache 为空。大量并发查询触发 Remote 拉取，50 个 Warm 节点 × 200MB/s = 10GB/s Remote 压力，可能超出对象存储带宽限制 |
| A-2 | **Master 过载成为单点瓶颈** | 高 | §5-6 | Master 集中承担：TieringPolicyService 评估、AutoscalingService 决策、AllocationService 分配、TieringMetadata 管理、孤儿文件调度。万级索引+频繁弹性场景下 Master 可能过载 |
| A-3 | **TieringService 并发流转资源竞争** | 中 | §5.8 | concurrent_transitions.max=5 允许 5 个大索引同时 flush + Remote sync，IO + 带宽竞争可能导致单个流转超时回滚，进入 retry loop |
| A-4 | **Hot 节点缩容 PreStop 超时数据丢失** | 中 | §6.5 | PreStop "等待分片迁移(最多 10min)"超时后 Pod 被强杀，若 forceUpload 未完成则 tail 数据丢失 |
| A-5 | **Coordinator 频繁接收大 ClusterState** | 中 | §4.2 | 万级 shard 集群 ClusterState 可达百 MB。Master 频繁更新（Tiering + allocation）导致 Coord 频繁接收 state diff，网络 + GC 压力 |

### 8.3 性能风险

| # | 风险 | 严重度 | 关联章节 | 分析 |
|---|------|--------|---------|------|
| P-1 | **Write-Back 上传与写入 IO 竞争** | 高 | §3.1-3.4 | Primary NVMe 同时承载写入 + refresh merge + segment upload 读取。upload 8 并发 × 256MB = 最大 2GB in-flight 读取，与实时写入竞争 IOPS |
| P-2 | **SharedBlobCache 16MB Region 读放大** | 中 | §4.3 | 只需 1KB posting list 也要拉取 16MB Region。高 cardinality terms query 离散读取时读放大可达 1000x+，Cold 层尤其严重 |
| P-3 | **LSR LayeredDirectory segment merge 路由歧义** | 中 | §2.8.5 | LayeredDirectory 通过 segmentMaxSeqno > LUS 判断走 tail/Remote。但 merge 后新 segment 包含混合 seqno 范围（部分 > LUS、部分 ≤ LUS），无法简单二分路由 |
| P-4 | **Adaptive Replica Selection 跨层精度不足** | 低 | §4.2 | 静态权重评分公式对跨层场景不适配。Hot 响应快但承载写入压力，Warm 无写入但 cache miss 高。可能导致路由偏斜 |

### 8.4 架构设计风险

| # | 风险 | 严重度 | 关联章节 | 分析 |
|---|------|--------|---------|------|
| S-1 | **单写者锁对 Remote Store 可用性的强依赖** | **极高** | §3.5, §7.3 | CAS 锁存储在 Remote Store 上。Remote 分区（部分路径可达、锁路径不可达）时：Primary 续约失败 → 触发切主 → 新 Primary 也获取不了锁 → 集群写入完全中断。降级模式的触发判断本身也依赖 Remote |
| S-2 | **4 种副本类型的语义复杂度** | 中 | §1.4, §2.5 | Sync Replica、LSR、Async Replica、Cold Replica 各自可见性保证、恢复路径、存储要求不同。AllocationService 需理解 4 种语义做调度决策，bug 概率高 |
| S-3 | **TieringPolicy 与 Autoscaler 决策冲突** | 中 | §5.4, §6 | TieringPolicy 将索引 Hot→Warm 释放空间，同时 Autoscaler 检测 Hot 磁盘降低触发缩容。缩容可能先于 Tiering 完成，移除正在流转的节点 |
| S-4 | **ES 7.17.4 基线版本技术债** | 高 | 全文档 | 无原生 Searchable Snapshot Directory（8.x）、无 DesiredBalance Allocator（8.6+）、Segment Replication 需完全自研（7.x 不支持）。开发量远超设计描述，需长期独立维护分叉 |
| S-5 | **Remote Store 对象存储 API 限制** | 中 | §3.4 | 万级 shard × 数百 segment = 数百万 keys。S3 限流 3500 PUT/5500 GET per prefix/s。设计未提及 prefix 分散策略 |

### 8.5 运维与操作风险

| # | 风险 | 严重度 | 关联章节 | 分析 |
|---|------|--------|---------|------|
| O-1 | **配置复杂度爆炸** | 中 | 全文档 | ~60+ 配置项三级嵌套（index/node/cluster）。`tail.max_size` 与 `upload.interval` 与 `backpressure.local_disk_threshold` 三者联动，配错一个可能连锁反应 |
| O-2 | **滚动升级期间 metadata 版本兼容** | 中 | §7.5 | 新旧版本 Primary/Replica 共存时，metadata 格式或 segment upload 协议变更可能导致旧节点无法理解新 metadata。设计未定义 metadata 版本兼容策略 |
| O-3 | **PITR 恢复粒度与承诺不匹配** | 低 | §7.4 | 保留策略"7d 后每小时快照"。用户需恢复 3 天前某秒数据时，只能恢复到小时级粒度 |
| O-4 | **混沌工程覆盖不足** | 低 | §7.9 | 未覆盖：Remote 部分不可达（单 prefix）、Master 脑裂、Autoscaler 误判连续扩容、时钟偏移导致 CAS 锁续约失败 |

### 8.6 Top-5 优先级排序

| 优先级 | 编号 | 风险名称 | 影响 |
|--------|------|---------|------|
| **P0** | S-1 | 单写者锁对 Remote 可用性强依赖 | Remote 分区时集群写入完全中断 |
| **P0** | D-2 | 单 AZ 故障时 ACK 数据丢失 | SLO 承诺与实际 RPO 矛盾 |
| **P1** | D-1 | LUS 推进后 LSR drop 可见性间隙 | 查询短暂返回不完整结果 |
| **P1** | A-2 | Master 过载单点瓶颈 | 万级索引场景下集群管理停滞 |
| **P1** | A-1 | Warm Cache-Miss 雪崩 | 弹性伸缩核心卖点 SLO 不达标 |

### 8.7 改进建议

#### 8.7.1 P0 — 单写者锁对 Remote 依赖(S-1)

**方案 A：锁服务分离**

将单写者锁从 Remote Store 中独立出来，存储在高可用 KV 系统：

```
选项                          优点                    缺点
──────────────────────────────────────────────────────────────
独立 etcd/ZooKeeper 集群      高可用,低延迟续约       额外运维组件
ES Master ClusterState 自身   无外部依赖               Master 故障时锁也不可用
双模式(Remote + Master 仲裁)  兼顾,Remote 可达时用     复杂度高
                              Remote,否则 Master 仲裁
```

推荐：**双模式**。正常时使用 Remote Store CAS 锁（兼容现有设计），Remote 不可达时退化为 Master 仲裁（基于 ClusterState primary_term）。

**方案 B：续约容忍窗口**

```yaml
# 新增配置
cluster.remote_store.single_writer.lease_tolerance: 30s   # 续约失败后容忍窗口
cluster.remote_store.single_writer.retry_before_failover: 3  # 重试次数后才触发切主
```

续约失败不立即触发切主，等待 3 次重试（30s 窗口）。窗口内 Remote 恢复则续约成功，否则进入降级模式。

#### 8.7.2 P0 — 单 AZ 故障数据丢失(D-2)

**措施 1：强制跨 AZ 副本分布**

```yaml
# 集群级强制配置
cluster.routing.allocation.awareness.attributes: zone
cluster.routing.allocation.awareness.force.zone.values: az-1,az-2,az-3
```

确保 Primary 和 Sync Replica 永远不在同一个 AZ。单 AZ 故障时至少一个副本存活。

**措施 2：关键索引默认 eager upload**

```yaml
# 对 RPO 敏感索引
index.remote_store.upload.eager_mode: true   # 每次 refresh 后立即上传
```

将 tail 窗口从 5s 压缩到 <1s，即使跨 AZ 副本策略失效（极端情况），数据丢失量也控制在 1s 以内。

**措施 3：文档明确 RPO 前提**

```
RPO <5s 前提条件:
1. Primary 与 Sync Replica 分布在不同 AZ(强制)
2. Remote Store 跨 AZ 冗余(S3 默认满足)
3. 若不满足条件 1,实际 RPO = tail 大小(可达分钟级)
```

#### 8.7.3 P1 — LUS 推进后 LSR drop 可见性间隙(D-1)

**方案：延迟 Drop + 可达性校验**

```
LUS 推进后的 drop 流程:
1. Primary broadcast LUS=N → LSR 收到
2. LSR 检查: segment(seqno ≤ N) 是否已在 Remote metadata 中可发现?
3. 若是 → 标记 tail segment 为 "droppable"(不立即删除)
4. 下一次查询成功从 Remote/FileCache 返回该范围数据后 → 真正 drop
5. 超时(60s)仍未被查询命中 → 直接 drop(防止无限积累)
```

```yaml
# 新增配置
index.remote_store.lsr.tail_drop_delay: 60s        # 延迟 drop 最大时间
index.remote_store.lsr.verify_remote_before_drop: true  # 开启可达性校验
```

#### 8.7.4 P1 — Master 过载(A-2)

**方案：职责下放 + 负载保护**

```
当前 Master 职责:
├── ClusterState 管理(不可下放)
├── AllocationService(不可下放)
├── TieringPolicyService 评估(可下放)
├── AutoscalingService 决策(可下放)
├── 孤儿文件调度(可下放)
└── TieringMetadata 管理(部分可下放)

改进架构:
Master:  仅执行 ClusterState 更新 + Allocation
Coord(选举 1 个为 Evaluator):  运行 TieringPolicy + Autoscaling 评估,向 Master 提交建议
任意数据节点:  孤儿文件扫描(Master 只触发调度信号)
```

```yaml
# 新增配置
cluster.master.load_protection.max_pending_tasks: 1000    # 超限暂停非关键评估
cluster.master.load_protection.pause_tiering_evaluation: true
cluster.tiering.evaluator_node_role: coord                # 评估逻辑在 Coord 运行
cluster.autoscaling.evaluator_node_role: coord            # 同上
```

#### 8.7.5 P1 — Warm Cache-Miss 雪崩(A-1)

**方案：多层防护**

```
防护层级:
L1. 扩容节流: 新节点每 30s 最多 1 个开始接收查询(串行预热)
L2. 全局带宽 Quota: Remote Store 全集群读取带宽硬上限
L3. 渐进权重: 新节点权重从 0% 线性升到 100%(30s ramp)
L4. 优先预热: 新节点优先加载 top-N 热点 segment 的 metadata + posting 起始
```

```yaml
# 新增配置
cluster.autoscaling.warm.max_concurrent_joins: 1             # 每 30s 最多 1 个新节点就绪
cluster.remote_store.read.global_bandwidth_limit: 5gb        # 全集群 Remote 读取硬上限
cluster.autoscaling.warm.ramp_initial_weight: 0.0            # 初始权重
cluster.autoscaling.warm.ramp_step: 0.1                      # 每 3s 增加 10%
node.prefetch.priority_segments: 10                          # 优先预热 top-N segment
```

### 8.8 补充混沌工程场景

| 类型 | 频率 | 验收标准 |
|------|------|---------|
| Remote 单 prefix 不可达 | 每月 | 受影响索引降级,其他索引不受影响 |
| Master 脑裂(网络分区 2+1) | 每季度 | 少数派 Master 自动 step down,无双写 |
| Autoscaler 误判(Mock 指标飙升) | 每月 | 不超过 max 边界,cooldown 生效 |
| 时钟偏移 5s(NTP 故障) | 每月 | CAS 锁续约不失败(基于 TTL 而非时间戳) |
| 50 个 Warm 节点同时重启 | 每季度 | Remote 带宽不超 quota,SLO 10min 内恢复 |
| Hot 节点 PreStop 超时(K8s 强杀) | 每月 | forceUpload 完成或数据可从 LSR 恢复 |

### 8.9 风险缓解实施优先级

```
Phase 1(M0-M3,与基础存算分离同步):
  ├── S-1: 实现续约容忍窗口(30s)+ 降级模式自动判断
  ├── D-2: 强制跨 AZ 副本分布配置
  └── D-3: Translog/Segment 上传顺序保证(先 translog 再 segment)

Phase 2(M3-M6,与分层架构同步):
  ├── D-1: LSR tail drop 延迟 + 可达性校验
  ├── P-3: LayeredDirectory merge segment 路由修正
  └── A-1: Warm 扩容节流 + 全局带宽 quota

Phase 3(M6-M9,与智能弹性同步):
  ├── A-2: Master 职责下放到 Coord
  ├── S-3: Tiering 与 Autoscaler 互锁(流转中禁止缩容)
  └── A-1: 渐进权重 + 优先预热完整实现

Phase 4(M9-M12,与极致优化同步):
  ├── S-1: 双模式锁(Remote + Master 仲裁)完整实现
  ├── S-5: S3 prefix 分散策略
  ├── O-2: metadata 版本兼容框架
  └── 补充混沌工程场景全覆盖
```

### 8.10 主从切换对集群可用性影响分析

#### 8.10.1 正常切主的读写中断时间线

```
时间线          写入状态              读取状态              内部动作
────────────────────────────────────────────────────────────────────────────────
T+0s           写入失败(reject)      Coord 仍路由旧 Primary  Primary 失联
T+0~5s         客户端收到异常         路由旧 Primary → 超时   fd.leader_check 检测中
                                                           (interval=1s, retry=3)
T+5s           等待新 Primary        读取可部分恢复          Master 确认节点离开
                                    (若有 Warm Async        AllocationService 选 LSR
                                     Replica 可读,滞后1-2s)
T+5~10s        等待                  部分可读               LSR 提升 tail segments
T+10~15s       等待                  部分可读               获取 Remote 单写者锁
T+15~20s       等待                  部分可读               ClusterState 更新中
T+20~25s       ▼ 写入恢复            ▼ 读取完全恢复          新 Primary 就绪
                                                           ClusterState 传播完成
────────────────────────────────────────────────────────────────────────────────
正常场景总计:   写入中断 ~20-25s       读取中断 ~5-25s(取决于是否有 Warm/其他副本)
```

#### 8.10.2 导致长时间不可用的异常场景

| 场景 | 写入中断 | 读取中断 | 根因 |
|------|---------|---------|------|
| **Remote Store 不可达** | **60-120s+** | 部分可读(有副本时) | 新 Primary 获取单写者锁超时 + 降级模式判断延迟(heartbeat 30s × N 次) |
| **LSR tail 膨胀(50GB+)** | 60-120s | 60-120s | 提升 tail segments 时间 ∝ tail 大小,50GB ≈ 60-90s |
| **热点节点崩溃(100 Primary)** | **数分钟** | 数分钟 | 100 个 shard 串行/低并发提升,每个 25s = 理论 500s |
| **Master 同时故障(级联)** | 60-90s | 60-90s | 需先完成 Master 选举(30-60s)再开始分片切主 |
| **网络分区(脑裂)** | 少数派永久中断 | 少数派读旧数据 | 少数派分区无法选主也无法提升 Primary |
| **无 in-sync 副本(仅 Async)** | 60-75s + 数据丢失 | 60-75s | 需等 unassigned timeout(60s)后从 Remote 冷恢复,tail 丢失 |
| **降级模式进入延迟** | 额外 30-90s | 同上 | heartbeat interval=30s × 连续失败次数 才确认 Remote 不可达 |

#### 8.10.3 各场景详细分析

**场景 A：Remote Store 不可达时切主**

```
T+0s:   Primary 宕机
T+5s:   Master 检测到,选择 LSR 提升
T+10s:  LSR 提升 tail segments 完成
T+15s:  尝试获取 Remote 单写者锁 → 超时(10s)
T+25s:  第 1 次重试 → 超时
T+35s:  第 2 次重试 → 超时
T+45s:  第 3 次重试 → 超时
         ↓
T+45s:  问题: 此时降级模式是否已触发?
         Remote heartbeat interval = 30s
         若 heartbeat 也在 T+15s 开始失败:
           T+45s = 仅 1 个 heartbeat 周期,不足以确认 Remote 不可达
           T+75s = 2 个周期,可能触发降级
         ↓
T+75s:  降级模式生效 → 跳过锁 → 写入恢复
```

**实际写入中断: 75-90s（远超声称的 RTO<30s）**

**场景 B：热点节点崩溃(100 个 Primary)**

```
假设: Hot-1 承载 100 个 Primary shard, 提升并发度=5

处理批次:
  Batch 1 (shard 1-5):    T+5s → T+30s     (25s/batch)
  Batch 2 (shard 6-10):   T+30s → T+55s
  Batch 3 (shard 11-15):  T+55s → T+80s
  ...
  Batch 20 (shard 96-100): T+480s → T+505s

最后一个 shard 写入恢复: T+505s ≈ 8.4 分钟
集群级写入可用性: 从 T+30s 开始逐步恢复,但到 T+505s 才 100% 恢复
```

**场景 C：Master 级联故障**

```
T+0s:   同 AZ 的 Hot-1(Primary) + Master-1(Leader) 同时故障
T+0~30s: 剩余 Master 选举(cluster.election.initial_timeout=100ms,
         但需要发现 leader 缺失 → 一轮 fd 检测 → 投票 → 确认)
         实际耗时: 10-30s(取决于 fd 间隔和网络)
T+30s:  新 Master 产生
T+30~35s: 新 Master 从 follower 中恢复 ClusterState
T+35s:  开始处理分片切主(正常流程 25s)
T+60s:  写入恢复

总计写入中断: ~60s
```

#### 8.10.4 读取可用性的特殊问题

**Coordinator 路由超时问题:**

```
场景: Primary 宕机,Coord 尚未收到 ClusterState 更新

1. Client → Coord: 发送搜索请求
2. Coord: 按当前 ClusterState 路由到已宕机 Primary
3. Coord: 等待 transport timeout (默认 30s)
4. Coord: 超时后尝试下一个副本(如果有)
5. 副本返回结果

客户端体感: 前 30s 卡住,然后才返回结果
即使 Warm Async Replica 完全可用,客户端仍需等待首次超时
```

**LSR 提升期间不可读:**

```
当前设计: LSR 在提升过程中(T+5~25s)不接受读请求
原因: 正在执行 tail segment 合并 + 状态切换,Lucene Directory 不稳定

影响: 如果 Primary 是某 shard 唯一的 Hot 副本,
      且 Warm Async Replica 数据滞后不可接受(如写后即查场景),
      则该 shard 读取中断全程 25s
```

**各副本配置下的读取可用性:**

| 副本配置 | Primary 故障时读取 | 中断时长 | 数据新鲜度 |
|---------|-------------------|---------|-----------|
| Primary + Sync Replica(Hot) | Sync Replica 可读 | **<5s** | 完全一致 |
| Primary + LSR only | 不可读(LSR 提升中) | **20-25s** | - |
| Primary + LSR + Async(Warm) | Warm 可读 | **<5s** | 滞后 1-2s |
| Primary only(无副本) | 完全不可读 | **60-75s** | 从 Remote 恢复 |

#### 8.10.5 当前设计的可用性缺口

| 缺口 | 影响 | 严重度 |
|------|------|--------|
| Primary 提升无并发度控制 | 热点节点崩溃时百级 shard 串行恢复,分钟级中断 | **高** |
| Remote 锁获取无快速降级 | 锁超时 + heartbeat 判断 = 75-90s 写入中断 | **高** |
| Coordinator 路由超时过长 | 有副本可用但客户端等 30s | **高** |
| LSR 提升期间不可读 | 浪费 LSR 本地 tail 数据可提供的读取能力 | 中 |
| 无 Primary shard 打散策略 | 单节点承载过多 Primary 形成热点 | 中 |
| 缺少 pre-selected failover target | 故障时还需选举 → 额外延迟 | 中 |

#### 8.10.6 改进方案

**1. 并发 Primary 提升**

```yaml
# 新增配置
cluster.routing.allocation.primary_promotion.concurrent: 20    # 默认 20 并发
cluster.routing.allocation.primary_promotion.batch_interval: 1s # 批次间隔
```

效果：100 个 shard 恢复从 500s → 5 批 × 25s = 125s，再优化后可达 50s。

**2. 单写者锁快速降级**

```yaml
# 新增配置
cluster.remote_store.single_writer.fast_degrade_on_first_failure: true
cluster.remote_store.single_writer.degrade_after_failures: 2    # 2 次失败即降级(非等 heartbeat)
cluster.remote_store.single_writer.lock_attempt_timeout: 5s     # 单次锁获取超时从 10s→5s
```

效果：Remote 不可达时写入中断从 75-90s → 15-20s（2 次失败 × 5s + 切换 5s）。

**3. Coordinator 快速失败路由**

```yaml
# 新增配置
cluster.search.fast_failover.enabled: true
cluster.search.fast_failover.known_dead_timeout: 2s    # 已知宕机节点立即跳过
```

改进逻辑：
```
收到 Master 的 node-left 事件 → Coord 立即标记该节点所有 shard 为 "unavailable"
后续路由: 跳过 unavailable shard → 直接路由到可用副本
超时策略: 对 unavailable 节点请求不等 30s,改为 2s 快速失败

效果: 读取中断从 30s → 2-5s
```

**4. LSR 提升期间允许读取**

```
改进: LSR 提升过程中,tail 数据仍在本地 → 可以提供读服务
实现: 提升期间 LSR 进入 "read-only serving" 模式
      - 接受并响应搜索请求(基于当前 tail + Remote)
      - 拒绝写入(直到提升完成)
      - Lucene Directory 使用 snapshot 视图(不受提升过程影响)

效果: LSR 作为唯一副本时,读取中断从 25s → 0s
```

**5. Primary 打散与预选 failover target**

```yaml
# Primary 打散(已有机制,强化配置)
cluster.routing.allocation.total_shards_per_node: 50            # 总 shard 上限
cluster.routing.allocation.primary_shards_per_node: 20          # Primary 专项上限

# 预选 failover target(新机制)
cluster.routing.allocation.primary_promotion.preselect: true
```

预选逻辑：
```
Master 在 ClusterState 中为每个 Primary 预先标记 failover target LSR:
  - 选择 tail 最新、延迟最低的 LSR
  - 标记存入 RoutingTable

故障发生时:
  - 跳过"选择最佳副本"阶段(已预选)
  - 直接通知预选 LSR 提升
  - 节省 5-10s 的选举和比较时间

效果: 正常切主 RTO 从 25s → 15s
```

#### 8.10.7 改进后的目标中断时间

| 场景 | 当前写入中断 | 改进后 | 当前读取中断 | 改进后 |
|------|-------------|--------|-------------|--------|
| 正常切主 | 20-25s | **≤15s** | 5-25s | **≤5s** |
| Remote 不可达 | 75-90s | **≤20s** | 5-30s | **≤5s** |
| 热点崩溃(100 shard) | 500s | **≤50s** | 500s | **≤5s**(有副本) |
| Master 级联 | 60s | 60s(不可优化) | 60s | **≤35s** |
| 无 in-sync 副本 | 60-75s | 60-75s(不可优化) | 60-75s | 60-75s |

```yaml
# 完整配置集合
cluster.routing.allocation.primary_promotion.concurrent: 20
cluster.routing.allocation.primary_promotion.preselect: true
cluster.routing.allocation.primary_shards_per_node: 20
cluster.remote_store.single_writer.fast_degrade_on_first_failure: true
cluster.remote_store.single_writer.degrade_after_failures: 2
cluster.remote_store.single_writer.lock_attempt_timeout: 5s
cluster.search.fast_failover.enabled: true
cluster.search.fast_failover.known_dead_timeout: 2s
index.remote_store.lsr.serve_reads_during_promotion: true
```

#### 8.10.8 可用性 SLO 对账

```
写入可用性目标: 99.95% (允许 ~22min/月 中断)
读取可用性目标: 99.99% (允许 ~4.3min/月 中断)

改进前预估(每月 3 次切主 + 1 次异常):
  写入: 3×25s + 1×90s = 165s/月 → 99.99% ✓ (但异常可一次性耗尽)
  读取: 3×25s + 1×30s = 105s/月 → 99.99% (勉强)

改进后预估:
  写入: 3×15s + 1×20s = 65s/月 → 99.99% ✓
  读取: 3×5s + 1×5s = 20s/月 → 99.999% ✓

结论: 改进后正常运行可满足 SLO。
      残余风险: Master 级联 + 热点崩溃 同时发生仍可能超标,
      通过 Primary 打散 + 跨 AZ 部署将此概率降至 <10^-4/月
```

---

## 第 9 章 多阶段演进路线

### 9.1 四阶段总览

| Phase | 时间 | 目标 | 关键交付 |
|-------|------|------|---------|
| Phase 1 | M0-M3 | 基础存算分离 | Remote Store write-back, Doc Replication, Hot+Remote |
| Phase 2 | M3-M6 | 分层架构 | Warm/Cold+FileCache, LSR, TieringService, TieringPolicy |
| Phase 3 | M6-M9 | 智能弹性 | Autoscaler, K8s/ECK, promote_to_hot, 大促编排 |
| Phase 4 | M9-M12 | 极致优化 | 跨 Region DR, PITR, Prefetch, OTel, 混沌工程 |

### 9.2 Phase 1:基础存算分离(M0-M3)

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

### 9.3 Phase 2:分层架构(M3-M6)

**目标**:引入 Warm/Cold,LSR 降低 Hot 成本 85%,TieringService 自动流转

**任务**:
- M3-M4(4周):SharedBlobCache + SparseFileTracker + LayeredIndexInput + LFU
- M4-M5(4周):LayeredDirectory + TailDirectory + LeanSyncReplicaEngine + LUS broadcast
- M5-M6(4周):TieringService 状态机 + HotToWarm/WarmToCold + TieringPolicy 集成

**验收**:
- TieringPolicy 自动 Hot→Warm→Cold
- 流转期间查询不中断
- LSR 存储 ≤ 全量 15%
- Warm P99 ≤ 200ms(cache hit 80%+)
- TCO 较纯 Hot 降 60%+

### 9.4 Phase 3:智能弹性(M6-M9)

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

### 9.5 Phase 4:极致优化(M9-M12)

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

### 9.6 里程碑

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

### 9.7 团队配置

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
