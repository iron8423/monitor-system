# 雷达 CSV 回放适配器（角色 B · 第一步）

> 作用：把真雷达导出的 CSV（`点形变雷达系统\web_存档\目标信号强度-YYYYMMDD\TargetN-RSSI-YYYYMMDD.csv`）按《雷达标准消息契约_v1》转换成**标准消息**，按时间顺序回放，喂给系统的 `/ingest/measurements`。用它就能**用真实数据跑通**“模拟/真实雷达 → 系统 → 3D/曲线”。
> 前提：你的机器上装了 **Python 3**（雷达后端就是 Python，肯定有）。日常目录里的 CSV 是 UTF-8 带 BOM，脚本已兼容。
> ⚠️ **数据源注意**：真正有 `累积形变`（监测数据）的 CSV 在 **`web_存档`** 里；根目录 `目标信号强度-*` 是“扫到目标”的原始日志，几乎全是 `监测=0`、`累积形变` 为空。脚本**默认目录已指向 `web_存档`，且默认只回放“监测=1 且有累积形变”的行**（去空数据）。
> 🎯 推荐演示数据：**`--date 20260827`**（这一天 Target1~Target5 各 277~291 行变形数据，正好 5 个监测点）。

## 2 种用法

### ① 先 dry-run（什么也不发送，只打印消息，核对格式）
```powershell
python radar_csv_replay.py --date 20260827 --max-events 8
```
看到打印出来的 JSON，字段（defo_mm / rate_mm_d / position / signal / quality…）对了，再加 `--send`。

### ② 真正回放到系统
```powershell
# 回放 20260908 这一天（默认 POST 到 http://127.0.0.1:8080/api/v1/ingest/measurements，每秒 1 条）
python radar_csv_replay.py --date 20260827 --send --speed 5 --max-events 200

# 快点回放：每秒 5 条
python radar_csv_replay.py --date 20260827 --send --speed 5

# 注入“超限” → 触发告警
python radar_csv_replay.py --date 20260827 --send --inject-overlimit

# 注入“断连” → 模拟设备离线（掐掉中间一段时间）
python radar_csv_replay.py --date 20260827 --send --inject-outage

# 注入“重复” → 每条发两遍，测系统幂等去重
python radar_csv_replay.py --date 20260827 --send --inject-duplicate
```

## 常用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--root` | `C:\Users\ASUS\Desktop\实习\点形变雷达系统\web_存档` | 真雷达 CSV（有变形数据的那份） |
| `--date` | 全部 | 只回放某天，如 `20260908` |
| `--target` | 全部 | 只回放某个目标，如 `1` |
| `--endpoint` | `http://127.0.0.1:8080/api/v1/ingest/measurements` | 系统 ingest 接口 |
| `--send` | 关 | 真正 POST；不加只打印 |
| `--speed` | 1 | 每秒条数 |
| `--max-events` | 不限 | 最多条数 |
| `--device` | `radar-001` | 设备 ID |
| `--point-prefix` | `TARGET-` | 测点编码前缀（Target1→TARGET-1） |
| `--include-unmonitored` | 关 | 把监测=0、无变形值的行也回放（默认只放有变形值的监测行） |
| `--inject-overlimit` / `--inject-outage` / `--inject-duplicate` | 关 | 分别注入超限 / 断连 / 重复，用于测告警、离线、幂等 |

## 字段映射（CSV → 标准消息）

| CSV 列 | 标准消息 | 说明 |
|---|---|---|
| 时间 | `collectTime` | 拼文件夹日期成 ISO8601(+08:00) |
| 信号强度 | `signal` | 0~1 |
| X坐标(°) | `position.angleDeg` | 角度 |
| Y坐标(m) | `position.distanceM` | 距离 |
| 状态 | `state` | 1→normal，否则 suspicious |
| 监测 | `monitored` | 1 且 有累计形变 才算有效 |
| 累积形变 | `metrics.defo_mm` | 点形变主指标 |
| （推算） | `metrics.rate_mm_d` | 由相邻两条 defo/时间差算速率 |

> `messageId` 用确定性 `uuid5(deviceId|pointCode|collectTime|sequence)` 生成，保证同一条数据 id 稳定，方便系统幂等去重。

## 状态
- 已适配 UTF-8(BOM) 的中文表头。
- 主识别 `TargetN-RSSI-YYYYMMDD.csv` 时间序列（含 `累积形变` 列）。根目录那种 `2026-08-27_09-54-08.csv`（单帧目标列表）暂不用于回放，只用于看目标清单。
- **注意**：`--send` 前请确认系统 ingest 接口已在跑（阶段 1 后端 /ingest）。
