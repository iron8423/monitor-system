# 雷达数据模拟器（tools/radar_simulator）

> 作用：**按《雷达标准消息契约_v1》连续造数**，喂给 `/api/v1/ingest/measurements`，让系统当下就在报数。
> 它是阶段 1 验收链的**第一环**：`模拟器 → ingest（校验/去重）→ 落库 → 规则触发 → 警情生成 → 处置留痕`。
> 前提：Python 3（只用标准库，无需 pip 安装任何东西；已就绪的后端在跑）。

## 它和 `tools/radar_csv_replay/` 有什么不同

两者都发同一条契约消息，可以并存，分工不同：

| | `radar_csv_replay/`（回放器） | `radar_simulator/`（本工具） |
|---|---|---|
| 数据来源 | **真雷达导出的 CSV**（默认指向 B 的 Windows 桌面路径） | 无，现场生成 |
| 解决什么 | 「把真雷达的**历史数据**搬进来」 | 「让它**现在就在报数**」 |
| 拿不到数据时 | 一步也跑不了 | 照常跑 |
| 现场演示 | 演示历史曲线 | 演示实时上报、超限告警、幂等、离线 |

验收套件 01–07 全部用 curl 合成报文，等于**绕过了链子的起点**——链路后半段验得很扎实，但「模拟器」这一环从没被跑过。本工具把起点补上，套件 `08-simulator.sh` 断言它造出来的数据确实能走完整条链。

## 怎么跑

```bash
# ① 先 dry-run：只在控制台打印消息，核对格式（不发送）
python3 radar_simulator.py --dry-run

# ② 真正上报：7 个种子测点，每 5s 一轮，一直跑（Ctrl-C 停）
python3 radar_simulator.py

# ③ 跑 10 轮就停（演示/自动化用）
python3 radar_simulator.py --interval 1 --count 10
```

跑起来长这样：

```
模拟器启动：7 个测点 / 设备 radar-001 / 间隔 5.0s
端点：http://127.0.0.1:8080/api/v1/ingest/measurements
模拟时钟：2026-09-10T15:12:03+08:00 起，每轮 +30 分钟
[15:12:03] 第 1 轮：7 条 -> accepted=14 rejected=0 duplicates=0
```

一条消息含 `defo_mm`/`rate_mm_d` 两个测项，所以 7 条消息 **`accepted=14` 行**（按落库行数计）——这不是发多了，是契约 §5 D2 的拆行。

## 注入：把验收第 2/3 条演出来

```bash
# 超限 → 触发告警：默认 P-HK01 阶梯 +3.2 → +4.2 → +5.6（前者 warning，后者升到 alarm）
python3 radar_simulator.py --inject-overlimit

# 再回落 → 看自动解除
python3 radar_simulator.py --inject-overlimit --recover-after 6

# 重复上报 → 验幂等（每轮原样重发，messageId 不变）
python3 radar_simulator.py --inject-duplicate

# 断连 → 停报，看设备被判离线
python3 radar_simulator.py --inject-outage --outage-after 3

# 可疑数据 → 超限值标 quality=SUSPECT，质量闸门应拦下（不产生警情）
python3 radar_simulator.py --inject-suspect
```

| 注入 | 断言什么 | 对应验收 |
|---|---|---|
| `--inject-duplicate` | 同 `messageId` 重发不重复写库（`duplicates=1`，曲线只 +1 点） | 第 2 条 |
| `--inject-overlimit` | 超限触发 → 等级升级 → 回落自动解除，全程**只 1 条**警情 | 第 3 条 |
| `--inject-outage` | 设备静默满 5 分钟被判离线，生成设备告警 | 第 5 条 |
| `--inject-suspect` | `SUSPECT` 的超限值不参与告警判定 | 契约 §3 |

### `--inject-outage` 不会立刻出告警，这是**对的**

停止上报后设备**不会立刻**被判离线。判据是 `DeviceStatusPolicy.OFFLINE_MINUTES = 5`（5 分钟没有 `last_report_time` 才算离线），再由 `DeviceAlarmMonitor` 按 `monitor.device-offline.sweep-ms`（默认 10s）扫出来。所以离线告警要**等满 5 分钟**——这是真实的业务判据，脚本不替它走捷径，只把话说清楚（启动时会打印这一行，结束时会再提醒一次）。

## 模拟时钟：为什么有 `--step-minutes`

真实雷达约 5 秒报一次，而累积形变是以「**天**」为尺度缓慢漂移的。如果让形变按墙钟时间随机游走，再把相邻两次形变除以 5 秒**反推**速率，会得到几千 mm/d 的荒唐值（第一版就是这么写的）。

本工具反过来：**速率自己做缓慢随机游走**（真实量级 ±0.3 mm/d，限幅 ±2），**形变按 `速率 × 模拟步长` 积分**。两者天然自洽，速率值也像真的。于是有了模拟时钟：

- `collectTime` = 模拟时钟，每轮走 `--step-minutes`（默认 30），不是墙钟；
- 所以 `--count 3 --step-minutes 60` 造出的曲线，相邻点是**整 3600 秒**间隔；
- `--backdate-days 2` 把起点往前拨，跑出来的是**历史曲线**而不是一排未来时间戳。

注入超限时速率**仍报当前漂移率**，不按这一跳反推——注入是人工试验刺激，不是物理漂移，按它反推会得到 100+ mm/d，把整条 rate 曲线带偏。

## 常用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--url` | `http://127.0.0.1:8080/api/v1/ingest/measurements` | ingest 端点 |
| `--key` | `dev-ingest-key` | `X-Ingest-Key`（env `MONITOR_INGEST_KEY` 覆盖） |
| `--device` | `radar-001` | 设备码，**必须在设备档案里存在**，否则整条被拒 |
| `--points` | `P-HK01…P-BP04`（种子 7 点） | 逗号分隔的测点业务编码，同样必须在档案里 |
| `--interval` | `5.0` | 每轮间隔秒数（墙钟） |
| `--count` | `0` | 跑几轮；0 = 一直跑 |
| `--once` | 关 | 只跑一轮（等同 `--count 1 --interval 0`，套件用） |
| `--step-minutes` | `30` | 每轮代表多少**模拟时间**（写 `collectTime` + 用于形变积分） |
| `--backdate-days` | `0` | 模拟时钟往前拨几天 |
| `--seed` | 无 | 随机种子，便于复现 |
| `--dry-run` | 关 | 只打印不发送 |
| `--inject-overlimit` / `--overlimit-point` / `--overlimit-steps` / `--overlimit-hold` / `--recover-after` / `--recover-value` | 见上 | 超限阶梯与回落 |
| `--inject-duplicate` / `--inject-suspect` / `--inject-outage` / `--outage-after` | 见上 | 幂等 / 质量闸门 / 断连 |

## 两处刻意「报错退出」的校验

静默失效比报错危险得多，所以下面两种直接 `exit 2`：

1. **`--overlimit-point` 不在 `--points` 里** —— 注入会完全不生效，但脚本照常跑完、输出一片正常，你会以为「超限演示过了」。
2. **`--points` 为空** —— 没有任何测点可造数。

套件 08 的 ⑥ 节专门断言这两条退出码。

## 单独跑（配合验收）

```bash
# 造在那次验收新建的临时测点上，保证可重复
python3 radar_simulator.py --once --points P-SIM-xxx            # 一轮
python3 radar_simulator.py --count 3 --interval 0 --step-minutes 60 --seed 12
```

## 状态

- 契约对齐 `docs/message-contract.md`（M0 冻结版）：`schemaVersion`/`messageId`/`sequence`/`metrics`/`position`/`signal`/`state`/`quality`。
- 只用 Python 3 标准库（`urllib`），无第三方依赖。
- 已被 `tools/acceptance/08-simulator.sh` 覆盖（17 条断言）。
