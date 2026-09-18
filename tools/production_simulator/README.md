# 生产候选版综合模拟与验收数据

本工具面向首期基线：**10 台雷达、1000 个测点、每点 2 个测项、5 秒采样、150 轮（12.5 分钟采集跨度）**。它不替代真实设备验收，
而是在雷达转换器完成前，尽可能提前发现数据契约、数据库、告警、实时推送和 3D 渲染问题。

## 数据集内容

- 正常缓慢漂移与空间相关扰动；
- 渐进形变、突变、加速形变、周期振荡、单点尖峰和恢复；
- `SUSPECT`、`FAULT`、低信号和目标消失；
- 精确重复消息、乱序到达、延迟上报；
- 单测点中断、整台设备中断、基准重置；
- 非法时间、未知设备/点号/测项、未知质量、错误 schema 等负向契约用例；
- 每一类异常对应的测点集合与预期结果（ground truth）。

## 生成

在项目根目录执行：

```bash
python3 tools/production_simulator/production_simulator.py generate \
  --output generated/production-baseline-20260916
```

得到：

| 文件 | 用途 |
|---|---|
| `catalog.sql` | 向独立测试数据库写入 10 台雷达、1000 点、标定关系及数字孪生配置 |
| `catalog.json` | 同一档案的机器可读版本 |
| `messages.ndjson.gz` | 正常与异常混合标准消息 |
| `contract-negative-cases.json` | 应拒收或待加固的非法消息 |
| `ground-truth.json` | 异常点集合和预期行为 |
| `manifest.json` | 数据集版本、参数与文件说明 |

生成器使用固定 seed，同一配置每次产生相同异常点和数值；消息时间以生成时刻为起点。`manifest.json`
同时记录每个文件的字节数和 SHA-256，可在传输后验证数据集是否完整。

生成后执行离线校验：

```bash
python3 tools/production_simulator/validate_dataset.py \
  generated/production-baseline-20260916
```

### 这份数据集与默认资产**没有**对应关系（复查清单 P2-8）

`manifest.json` 里有一条 `datasetScope: "independent-test-project"`，`validate_dataset.py`
会**断言**它存在且取值正确。原因很实际：本数据集的坐标是按 `scenario.production.json`
自己的锚点与参数生成的，而仓库里那几份默认资产（`frontend/public/models/qingyuan-*`）
是另一条流水线的产物——把这份数据当成"某个演示项目的生产数据"导入，得到的是一组
彼此对不上的坐标，而且要等到大屏上点位飘走才会有人发现。

要把它用在某个真实场景上，正确做法是**按该场景的 DEM / 位姿重新采样生成**，
而不是手工改锚点凑数；重新生成后 `datasetScope` 保持这个值（它描述的是"数据集的性质"，
不是"跑在哪台机器上"）。

它会检查文件哈希、消息数量、10×1000 档案规模，以及每条标定的量程、水平/垂直视场和 LOS 净空。

## 准备独立测试库

不要把 `catalog.sql` 直接导入真实生产库。建议使用单独的 PostgreSQL 数据库或临时数据卷，并确保后端已完成
Flyway V11。V11 增加雷达天线高度、垂直视场以及目标方位/俯仰/斜距/LOS 标定字段。示例：

```bash
docker compose exec -T db psql -U monitor -d monitor < generated/production-baseline-20260916/catalog.sql
```

档案使用项目 ID `900`、点 ID `10000+`、设备 ID `20000+`，避免与现有演示种子 1～9 冲突。

## 最大速率回放（接口/数据库压力）

```bash
python3 tools/production_simulator/production_simulator.py send \
  --input generated/production-baseline-20260916/messages.ndjson.gz \
  --rebase-now --ingest-mode REALTIME \
  --batch-size 1000
```

这会尽可能快地发送，用于测吞吐，不等价于真实墙钟运行。输出会累计：

```text
accepted / rejected / duplicates / batches / messages
```

每条正常消息含两个测项，所以正常情况下 `accepted` 是唯一有效消息数的两倍。

## 按墙钟或加速回放（实时页面/SSE）

```bash
# 原速：5 秒采样就每 5 秒发一轮
python3 tools/production_simulator/production_simulator.py send \
  --input generated/production-baseline-20260916/messages.ndjson.gz \
  --rebase-now --wall-clock --speed 1 --ingest-mode REALTIME

# 10 倍速
python3 tools/production_simulator/production_simulator.py send \
  --input generated/production-baseline-20260916/messages.ndjson.gz \
  --rebase-now --wall-clock --speed 10 --ingest-mode REALTIME
```

墙钟调度按 `receiveTime` 而不是 `collectTime`，因此延迟上报会真的晚 12 分钟送达。设备 10 在场景中静默
405 秒，原速回放能够覆盖 5 分钟离线触发及恢复；最大速率或加速回放不能用来证明时间窗口判据有效。

不使用 `--rebase-now` 时默认按 `BACKFILL` 接入，只补历史库，不更新在线状态、不发送 SSE、也不改变当前告警。
需要明确覆盖默认值时使用 `--ingest-mode REALTIME|BACKFILL`。

## 必须核对的验收项

1. 批量 ingest 无 500，合法唯一消息全部接收；
2. 重复消息只增加 `duplicates`，数据库行数不增加；
3. 1000 点大屏只发一个项目最新值请求；
4. 项目切换后旧模型、旧雷达和旧点全部消失；
5. 远景标签与热力圈数量服从数字孪生配置预算；
6. `SUSPECT/FAULT` 超限不产生测值告警；
7. 有效形变依次触发 warning、升级 alarm、回落解除；
8. 乱序旧数据能查询，但不能覆盖最新值；
9. SSE 断开后轮询能恢复快照；
10. GLB 加载失败时页面仍可操作并明确显示错误；
11. 3D Tiles 使用实际 tileset 时检查 LOD、内存预算和离线资源完整性；
12. `ground-truth.json` 中明确标记的当前缺口不得记为“通过”。
13. `catalog.json` 中每个测点的斜距、水平/垂直偏角均在设备能力内，且标定状态为 `ACTIVE + LOS`。

## 当前有意暴露的缺口

单测点/单测项中断和基准重置事件尚未建立完整业务模型。它们保留在数据集中，是为了防止验收报告把
未实现能力遗漏掉。契约负向用例在 `INGEST_STRICT_CONTRACT=true` 时必须按 `expected` 中的原因码拒收。
