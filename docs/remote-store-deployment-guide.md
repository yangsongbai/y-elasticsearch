# Elasticsearch 存算分离 (Remote Store) 部署与使用指南

> 基于 Elasticsearch 7.17.4，存算分离特性将索引数据持久化到远端对象存储（如 S3/OSS/MinIO），实现计算节点无状态化、弹性扩缩容和快速故障恢复。

---

## 目录

1. [架构概述](#架构概述)
2. [前置条件](#前置条件)
3. [部署配置](#部署配置)
4. [索引使用](#索引使用)
5. [数据分层 (Tiering)](#数据分层-tiering)
6. [弹性伸缩 (Autoscaling)](#弹性伸缩-autoscaling)
7. [灾备与恢复 (PITR)](#灾备与恢复-pitr)
8. [可观测性](#可观测性)
9. [运维操作](#运维操作)
10. [调优建议](#调优建议)
11. [故障排查](#故障排查)

---

## 架构概述

```
┌─────────────────────────────────────────────────────────┐
│                   Elasticsearch Cluster                   │
├──────────────┬──────────────┬───────────────────────────┤
│  Hot Nodes   │  Warm Nodes  │      计算节点 (无状态)      │
│  (读写)      │  (只读缓存)  │      弹性扩缩              │
├──────────────┴──────────────┴───────────────────────────┤
│              Remote Store Layer                           │
│  ┌────────────┐ ┌──────────────┐ ┌───────────────────┐  │
│  │ Segment    │ │ Translog     │ │ Single Writer     │  │
│  │ Upload     │ │ Transfer     │ │ Lock              │  │
│  ├────────────┤ ├──────────────┤ ├───────────────────┤  │
│  │ Scheduler  │ │ Backpressure │ │ Fast Failover     │  │
│  │ + Priority │ │ Controller   │ │ Service           │  │
│  └────────────┘ └──────────────┘ └───────────────────┘  │
├─────────────────────────────────────────────────────────┤
│              Object Storage (S3/OSS/MinIO)               │
└─────────────────────────────────────────────────────────┘
```

**核心组件：**

| 组件 | 功能 |
|------|------|
| RemoteStoreRefreshListener | 每次 Lucene refresh 后自动上传新 segment 文件 |
| SegmentUploadScheduler | 优先级队列 + 并发限制的 segment 上传调度器 |
| RemoteTranslogTransferManager | translog 文件的远端上传管理 |
| SingleWriterLock | 基于 blob store 的分布式锁，保证单写一致性 |
| BackpressureController | 基于磁盘使用率和连续失败的背压控制 |
| RelocationUploadService | 分片迁移时确保数据完整上传后再切换 |
| FastFailoverService | 节点离线时快速路由绕过 |
| SharedBlobCacheService | 本地 SSD 文件缓存，加速远端读取 |
| PrefetchService | 基于时间/元数据的预取策略 |
| TieringService | Hot → Warm → Cold 自动数据分层 |
| AutoscalingService | 基于延迟/队列/预测的自动扩缩容 |
| PITRService | 时间点恢复能力 |

---

## 前置条件

### 硬件要求

| 角色 | CPU | 内存 | 存储 | 网络 |
|------|-----|------|------|------|
| Hot 节点 | 16+ 核 | 64GB+ | NVMe SSD 1TB+ | 10Gbps+ |
| Warm 节点 | 8+ 核 | 32GB+ | SSD 500GB+ (缓存) | 10Gbps |
| 计算节点 | 8+ 核 | 32GB+ | SSD 200GB (缓存) | 10Gbps |

### 软件要求

- JDK 11 或 JDK 17
- Elasticsearch 7.17.4 (含存算分离补丁)
- 对象存储服务（S3 / 阿里云 OSS / MinIO 等）

### 对象存储准备

创建以下 bucket：

```
<prefix>-segments     # segment 数据
<prefix>-translogs    # translog 数据
<prefix>-metadata     # 元数据 & 锁文件
```

确保 ES 节点拥有对应 bucket 的读写权限（IAM / AK/SK）。

---

## 部署配置

### 1. 注册远端仓库

在 `elasticsearch.yml` 中配置 snapshot 仓库插件（以 S3 为例）：

```yaml
# elasticsearch.yml
s3.client.default.endpoint: "s3.cn-hangzhou.aliyuncs.com"
s3.client.default.protocol: "https"
```

启动后注册仓库：

```json
PUT _snapshot/remote_store_repo
{
  "type": "s3",
  "settings": {
    "bucket": "my-es-remote-store",
    "base_path": "remote-store/cluster-prod",
    "region": "cn-hangzhou",
    "compress": true
  }
}
```

### 2. 节点级配置 (elasticsearch.yml)

```yaml
# ==================== Remote Store 核心配置 ====================

# Segment 上传并发数 (每节点)
node.remote_store.segment.upload.parallelism: 8

# Segment 上传最大在途字节数
node.remote_store.segment.upload.max_bytes_in_flight: 256mb

# ==================== 背压控制 ====================

# 磁盘使用率告警阈值 (降速)
node.remote_store.backpressure.local_disk_threshold_warn: 0.70

# 磁盘使用率阻断阈值 (停写)
node.remote_store.backpressure.local_disk_threshold_block: 0.90

# 连续失败阈值 (触发背压)
node.remote_store.failure.consecutive_threshold: 5

# ==================== 文件缓存 (Warm/计算节点) ====================

# 缓存总大小 (建议: 本地 SSD 的 80%)
node.filecache.size: 200gb

# 缓存 region 大小
node.filecache.region_size: 16mb

# 淘汰策略: LFU_DECAY
node.filecache.eviction_policy: LFU_DECAY

# 频率衰减间隔
node.filecache.decay.interval: 1m

# 衰减因子 (0-1, 越小淘汰越快)
node.filecache.decay.factor: 0.95

# ==================== 预取 ====================

# 启用预取
node.prefetch.enabled: true

# 预取速率限制
node.prefetch.rate_limit: 200mb

# 预取并发数
node.prefetch.concurrency: 4

# 缓存占用达此比例时停止预取
node.prefetch.cache_disable_threshold: 0.80
```

### 3. 集群级配置 (动态可调)

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.remote_store.single_writer.enabled": true,
    "cluster.remote_store.single_writer.heartbeat_interval": "10s",
    "cluster.remote_store.single_writer.lease_tolerance": "30s",
    "cluster.remote_store.single_writer.degrade_after_failures": 2,
    "cluster.remote_store.single_writer.fast_degrade_on_first_failure": true,

    "cluster.routing.allocation.relocation.force_upload_before_handoff": true,

    "cluster.search.fast_failover.enabled": true,
    "cluster.search.fast_failover.known_dead_timeout": "2s",

    "cluster.routing.allocation.primary_promotion.concurrent": 20,
    "cluster.routing.allocation.primary_promotion.batch_interval": "1s",
    "cluster.routing.allocation.primary_promotion.preselect": true,
    "cluster.routing.allocation.primary_shards_per_node": 20
  }
}
```

---

## 索引使用

### 创建启用 Remote Store 的索引

```json
PUT /my-index
{
  "settings": {
    "index.remote_store.enabled": true,
    "index.remote_store.repository": "remote_store_repo",
    "index.translog.remote.upload.interval": "1s",
    "index.translog.remote.upload.batch_size": "4mb",
    "index.translog.remote.parallel_upload": 8,
    "index.number_of_shards": 6,
    "index.number_of_replicas": 1
  }
}
```

### 配置说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `index.remote_store.enabled` | `false` | 启用远端存储 |
| `index.remote_store.repository` | `""` | 远端仓库名称 |
| `index.translog.remote.upload.interval` | `1s` | translog 上传间隔 |
| `index.translog.remote.upload.batch_size` | `4mb` | 批量上传大小 |
| `index.translog.remote.parallel_upload` | `8` | translog 并行上传数 |
| `index.remote_store.relocation.handoff_timeout` | `60s` | 迁移切换超时时间 |
| `index.remote_store.relocation.max_tail_at_handoff` | `256mb` | 迁移切换时允许的最大尾部数据量 |

### 已有索引启用 Remote Store

已有索引需要通过 reindex 迁移：

```json
POST _reindex
{
  "source": { "index": "old-index" },
  "dest": {
    "index": "new-remote-index"
  }
}
```

### 验证远端存储状态

```json
GET /my-index/_remote_store/stats

# 响应示例
{
  "shards": {
    "0": {
      "last_uploaded_generation": 42,
      "bytes_pending": 0,
      "upload_in_progress": false,
      "total_uploaded_files": 156
    }
  }
}
```

---

## 数据分层 (Tiering)

基于索引年龄自动将数据在 Hot → Warm → Cold 之间流转。

### 配置分层策略

```json
PUT /my-index/_settings
{
  "index.tiering.warm_after": "7d",
  "index.tiering.cold_after": "30d",
  "index.tiering.delete_after": "365d"
}
```

### 集群级分层评估间隔

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.tiering.evaluation_interval": "5m"
  }
}
```

### 分层行为

| 阶段 | 存储方式 | 读取方式 | 写入 |
|------|----------|----------|------|
| Hot | 本地 NVMe + 远端实时同步 | 本地读取 | 支持 |
| Warm | 远端 + 本地 SSD 缓存 | 缓存优先，回源远端 | 只读 |
| Cold | 纯远端 | 按需下载 | 只读 |
| Delete | 远端清理 | - | - |

---

## 弹性伸缩 (Autoscaling)

基于实时指标自动调整计算节点数量。

### 启用 Autoscaling

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.autoscaling.enabled": true,
    "cluster.autoscaling.evaluation_interval": "30s",
    "cluster.autoscaling.cooldown.up": "30s",
    "cluster.autoscaling.cooldown.down": "5m",
    "cluster.autoscaling.rate.up": 1.0,
    "cluster.autoscaling.rate.down": 0.3,
    "cluster.autoscaling.warm.min": 2,
    "cluster.autoscaling.warm.max": 100,
    "cluster.autoscaling.hot.min": 3,
    "cluster.autoscaling.hot.max": 30
  }
}
```

### 扩缩容决策器

| 决策器 | 触发条件 | 配置 |
|--------|----------|------|
| Latency | P99 延迟超过阈值 | `cluster.autoscaling.deciders.latency.target_p99: 200ms` |
| Queue | 搜索队列深度超过阈值 | `cluster.autoscaling.deciders.queue.threshold: 100` |
| Predictive | 基于历史趋势预测 | `cluster.autoscaling.deciders.predictive.lookahead: 15m` |
| Storage (Reactive) | 存储使用率高 | 内置策略 |
| Storage (Proactive) | 预判存储增长 | 内置策略 |

### 查看扩缩容容量

```json
GET _autoscaling/capacity

# 响应示例
{
  "policies": {
    "warm": {
      "required_capacity": { "node": { "count": 5 } },
      "current_capacity": { "node": { "count": 3 } },
      "deciders": {
        "latency": { "reason": "p99=320ms > target=200ms" }
      }
    }
  }
}
```

### 与外部编排集成

Autoscaling 产出容量建议，实际扩缩需要通过 Kubernetes HPA 或自定义控制器执行：

```bash
# 示例：与 K8s 集成
# 1. 定时拉取容量建议
DESIRED=$(curl -s localhost:9200/_autoscaling/capacity | jq '.policies.warm.required_capacity.node.count')

# 2. 调整 StatefulSet
kubectl scale statefulset es-warm --replicas=$DESIRED
```

---

## 灾备与恢复 (PITR)

### 时间点恢复

查看可用恢复点：

```json
GET _remote_store/pitr/my-index

# 响应示例
{
  "recovery_points": [
    {
      "index": "my-index",
      "timestamp": 1719648000000,
      "generation": 42,
      "primary_term": 3
    }
  ]
}
```

恢复到指定时间点：

```json
POST _remote_store/pitr/my-index/_restore
{
  "target_timestamp": 1719648000000
}
```

### 恢复点保留策略

- 保留窗口内（默认 7 天）：全量保留每个恢复点
- 超出保留窗口：每小时保留一个恢复点
- 通过 `pruneRetention` 自动清理

---

## 可观测性

### OpenTelemetry 链路追踪

```yaml
# elasticsearch.yml
tracing.enabled: true
tracing.sampler.fraction: 0.1
tracing.exporter.type: otlp
```

追踪覆盖的关键路径：
- `segment_upload` — 每个 segment 文件的上传耗时
- `translog_upload` — translog 文件上传耗时
- `remote_read` — 远端读取操作
- `cache_miss` — 缓存未命中回源

### 监控指标

#### Remote Store 状态

```json
GET _nodes/stats/remote_store

# 关注指标
{
  "remote_store": {
    "segments": {
      "upload_bytes_succeeded": 1073741824,
      "upload_bytes_failed": 0,
      "upload_time_in_millis": 45230,
      "pending_bytes": 0
    },
    "translog": {
      "upload_bytes_succeeded": 536870912,
      "last_uploaded_generation": 100
    }
  }
}
```

#### 背压状态

```json
GET _remote_store/backpressure

# 状态级别
# NORMAL — 正常写入
# WARN — 降速告警 (连续失败 >= 阈值 且 磁盘 >= warn)
# BACKPRESSURE — 写入降速
# BLOCK — 写入阻断 (磁盘 >= block 阈值)
```

#### 分布式锁状态

```json
GET _remote_store/lock/status

# 响应
{
  "held_locally": true,
  "degraded": false,
  "consecutive_failures": 0,
  "last_successful_renew_ms": 1719648000000
}
```

### 告警建议

| 指标 | 阈值 | 级别 | 说明 |
|------|------|------|------|
| `pending_bytes > 0` 持续 5m+ | 256MB | WARN | 上传积压 |
| `upload_bytes_failed` 递增 | 任何失败 | WARN | 上传失败需排查 |
| `backpressure_level = BLOCK` | - | CRITICAL | 写入完全阻断 |
| `lock.degraded = true` | - | WARN | 锁降级，续约失败 |
| `lock.held_locally = false` | - | CRITICAL | 锁丢失，可能脑裂 |

---

## 运维操作

### 分片迁移 (Relocation)

启用 `force_upload_before_handoff` 后，分片迁移会确保所有数据上传完毕后再切换：

```json
# 查看迁移超时设置
GET /my-index/_settings?filter_path=*.settings.index.remote_store.relocation

# 调整超时 (大分片可能需要更长时间)
PUT /my-index/_settings
{
  "index.remote_store.relocation.handoff_timeout": "120s",
  "index.remote_store.relocation.max_tail_at_handoff": "512mb"
}
```

### 节点故障切换

启用 Fast Failover 后，搜索请求会自动绕过已知离线节点：

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.search.fast_failover.enabled": true,
    "cluster.search.fast_failover.known_dead_timeout": "2s"
  }
}
```

行为：
1. 节点离线 → 自动标记为 unavailable
2. 搜索请求跳过 unavailable 节点，路由到其他副本
3. 节点重新加入 → 自动清除标记

### Primary 快速提升

节点故障时批量提升 replica 为 primary：

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.primary_promotion.concurrent": 20,
    "cluster.routing.allocation.primary_promotion.batch_interval": "1s",
    "cluster.routing.allocation.primary_promotion.preselect": true
  }
}
```

### 手动触发 Segment 上传

```json
POST /my-index/_refresh

# refresh 后 RemoteStoreRefreshListener 会自动触发上传
# 通过 stats 验证
GET /my-index/_remote_store/stats
```

---

## 调优建议

### 写入密集型场景

```json
PUT _cluster/settings
{
  "persistent": {
    "node.remote_store.segment.upload.parallelism": 16,
    "node.remote_store.segment.upload.max_bytes_in_flight": "512mb"
  }
}

PUT /my-index/_settings
{
  "index.translog.remote.upload.interval": "500ms",
  "index.translog.remote.parallel_upload": 16
}
```

### 读取密集型场景

```yaml
# elasticsearch.yml (Warm/计算节点)
node.filecache.size: 400gb
node.filecache.region_size: 32mb
node.prefetch.enabled: true
node.prefetch.concurrency: 8
node.prefetch.rate_limit: 500mb
```

### 大规模集群 (1000+ 分片)

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.primary_shards_per_node": 40,
    "cluster.routing.allocation.primary_promotion.concurrent": 50,
    "cluster.remote_store.single_writer.heartbeat_interval": "15s"
  }
}
```

### 对象存储网络不稳定场景

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.remote_store.single_writer.lease_tolerance": "60s",
    "cluster.remote_store.single_writer.degrade_after_failures": 3,
    "cluster.remote_store.single_writer.fast_degrade_on_first_failure": true,
    "node.remote_store.failure.consecutive_threshold": 10
  }
}
```

---

## 故障排查

### 问题: 上传积压持续增长

**症状:** `bytes_pending` 持续不为 0，上传延迟增大

**排查步骤:**

```bash
# 1. 检查背压状态
GET _remote_store/backpressure

# 2. 检查节点磁盘
GET _cat/allocation?v

# 3. 检查对象存储连通性
# 查看 ES 日志中的上传失败信息
grep "Failed to upload segment" logs/elasticsearch.log

# 4. 检查上传并发是否打满
GET _nodes/stats/thread_pool/generic
```

**解决方案:**
- 磁盘使用率高 → 扩容或清理
- 对象存储超时 → 检查网络/提高超时
- 并发打满 → 增加 `segment.upload.parallelism`

### 问题: 分布式锁降级

**症状:** 日志中出现 `SingleWriterLock degraded`

**排查步骤:**

```bash
# 检查锁状态
GET _remote_store/lock/status

# 检查是否有网络抖动
grep "Lock" logs/elasticsearch.log | tail -20
```

**解决方案:**
- 临时网络抖动 → 锁会自动恢复（日志中出现 `recovered from degraded mode`）
- 持续降级 → 检查对象存储 bucket 权限和连通性
- `heldLocally=false` → 可能需要重启节点重新获取锁

### 问题: 分片迁移超时

**症状:** 分片迁移报 TIMEOUT

**排查步骤:**

```bash
# 检查未上传数据量
GET _cat/shards?v&h=index,shard,prirep,state,docs,store

# 检查 relocation 配置
GET /my-index/_settings?filter_path=*.settings.index.remote_store.relocation
```

**解决方案:**
- 调大 `handoff_timeout`
- 调大 `max_tail_at_handoff`
- 确保上传速率 > 写入速率

### 问题: 缓存命中率低

**症状:** 搜索延迟高，remote_read 频繁

**排查步骤:**

```bash
# 查看缓存统计
GET _nodes/stats/filecache

# 检查预取是否生效
GET _nodes/stats/prefetch
```

**解决方案:**
- 增大 `node.filecache.size`
- 检查预取策略是否覆盖热数据
- 调整衰减因子 `node.filecache.decay.factor` (越大保留越久)

### 问题: Autoscaling 不触发

**症状:** 延迟高但不扩容

**排查步骤:**

```bash
# 检查 autoscaling 是否启用
GET _cluster/settings?filter_path=*.cluster.autoscaling*

# 查看当前容量决策
GET _autoscaling/capacity

# 检查是否在 cooldown 中
```

**解决方案:**
- 确保 `cluster.autoscaling.enabled: true`
- 检查 cooldown 设置是否过长
- 检查 min/max 范围是否合理

---

## 滚动升级指南

### 从标准 7.17.4 升级到含存算分离的版本

1. **准备阶段**
   ```json
   PUT _cluster/settings
   { "persistent": { "cluster.routing.allocation.enable": "primaries" } }
   ```

2. **逐节点升级**
   ```bash
   # 禁用分片分配
   # 停止节点 → 替换二进制 → 更新 elasticsearch.yml → 启动节点
   # 等待节点加入集群
   # 恢复分片分配
   ```

3. **升级后启用 Remote Store**
   ```json
   # 全部节点升级完成后
   PUT _cluster/settings
   {
     "persistent": {
       "cluster.remote_store.single_writer.enabled": true,
       "cluster.search.fast_failover.enabled": true
     }
   }
   ```

4. **新索引启用远端存储**
   ```json
   # 对新创建的索引启用
   PUT /new-index
   {
     "settings": {
       "index.remote_store.enabled": true,
       "index.remote_store.repository": "remote_store_repo"
     }
   }
   ```

5. **迁移已有索引**（可选，按需执行）
   ```json
   POST _reindex
   {
     "source": { "index": "existing-index" },
     "dest": { "index": "existing-index-remote" }
   }
   ```

---

## 安全配置

### 对象存储访问凭证

**方式一: Keystore (推荐)**

```bash
bin/elasticsearch-keystore add s3.client.default.access_key
bin/elasticsearch-keystore add s3.client.default.secret_key
```

**方式二: IAM Role (云环境推荐)**

ES 节点绑定 IAM Role，无需配置 AK/SK。确保 Role 拥有以下权限：
- `s3:GetObject`
- `s3:PutObject`
- `s3:DeleteObject`
- `s3:ListBucket`

### 传输加密

```yaml
# elasticsearch.yml
s3.client.default.protocol: "https"
```

---

## 容量规划

### 对象存储容量

```
远端存储量 ≈ 本地索引大小 × (1 + translog 保留比例)
建议预留: 本地数据量 × 1.5
```

### 本地缓存容量

```
缓存大小 = 热数据量 × 缓存命中率目标
示例: 1TB 热数据 × 90% 命中率 → 需要约 200-300GB 缓存
```

### 网络带宽

```
所需带宽 = 写入吞吐量 × 2 (上传 segment + translog)
示例: 写入 100MB/s → 需要 200MB/s = 1.6Gbps 上行带宽
```
