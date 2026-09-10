#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
雷达数据模拟器（阶段 1 验收链的第一环：模拟器 -> ingest -> 落库 -> 规则 -> 警情 -> 处置）

为什么要它：`tools/radar_csv_replay/` 是**回放器不是生成器**——它要 `--root` 指向真雷达
导出的 CSV（默认值还是 B 的 Windows 桌面路径），拿不到数据就一步也跑不了。于是验收链的
最上游一直没人跑过，验收套件全用 curl 合成报文，等于绕过了起点。本脚本按
`docs/message-contract.md` 连续造数，不依赖任何外部数据。

与回放器的分工：回放器负责「把真雷达的历史数据搬进来」，本脚本负责「让它现在就在报数」。
两者都发同一个契约消息，可并存。

用法示例：
  python3 radar_simulator.py --dry-run                 # 只在控制台打印，不发送
  python3 radar_simulator.py                           # 7 个种子测点连续上报，每 5s 一轮
  python3 radar_simulator.py --interval 1 --count 10   # 跑 10 轮就停
  python3 radar_simulator.py --inject-overlimit        # 注入超限：+3.2 -> +4.2 -> +5.6
  python3 radar_simulator.py --inject-overlimit --recover-after 6   # 再回落，走完自动恢复
  python3 radar_simulator.py --inject-duplicate        # 每轮原样重发（同 messageId），验幂等
  python3 radar_simulator.py --inject-outage --outage-after 3       # 第 3 轮后停止上报
  python3 radar_simulator.py --inject-suspect          # 超限值 + quality=SUSPECT，验质量闸门

验收套件用法（跑在临时测点上，保证可重复）：
  python3 radar_simulator.py --once --points P-SIM-xxx --device <设备码>

关于 --inject-outage：停止上报后设备**不会立刻**被判离线。判据是
`DeviceStatusPolicy.OFFLINE_MINUTES = 5`（5 分钟没有 `last_report_time` 即离线），
再由 `DeviceAlarmMonitor` 按 `monitor.device-offline.sweep-ms`（默认 10s）扫出来。
所以离线告警要等满 5 分钟——这是真实的业务判据，脚本不替它走捷径，只把话说清楚。
"""
import argparse
import json
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

# 契约 §1：时间 ISO8601 带时区；全系统口径 Asia/Shanghai
TZ = timezone(timedelta(hours=8))

DEFAULT_URL = "http://127.0.0.1:8080/api/v1/ingest/measurements"
DEFAULT_KEY = "dev-ingest-key"
DEFAULT_DEVICE = "radar-001"
# 种子 7 测点（V2__seed_data.sql）：灰库 3 + 边坡 4
DEFAULT_POINTS = ["P-HK01", "P-HK02", "P-HK03", "P-BP01", "P-BP02", "P-BP03", "P-BP04"]
# 默认规则是 defo_mm 双向 ±3mm（gte +3.0 / lte -3.0），V4 另有 gte +5.0 升到 alarm 档
WARN_LEVEL = 3.0
ALARM_LEVEL = 5.0


class PointSim:
    """单测点的取值模型：**速率**主导、形变按速率积分。

    反过来的写法（形变走随机游走、速率由相邻两次形变除以墙钟时间推导）会算出
    天文数字：真实雷达约 5 秒报一次，而累积形变是以「天」为尺度缓慢漂移的，
    0.15mm 的抖动摊到 5 秒上就是几千 mm/d。这里让速率自己缓慢演化（真实量级
    ~0.3 mm/d），形变按 `速率 × 模拟步长` 累积——两者天然自洽，速率值也像真的。
    """

    def __init__(self, code, rng):
        self.code = code
        self.rng = rng
        self.defo = rng.uniform(-1.0, 1.0)          # 初始累积形变
        self.rate = rng.uniform(-0.3, 0.3)          # 当前速率 mm/d
        self.sequence = 0
        self.last_message = None                    # 供 --inject-duplicate 原样重发

    def _drift(self):
        """速率自身的缓慢随机游走，限幅在 ±2 mm/d（真实约 0.3，留足演示余量）。"""
        self.rate = max(-2.0, min(2.0, self.rate + self.rng.gauss(0.0, 0.08)))

    def next_values(self, step_days):
        """推进一步，返回 (defo_mm, rate_mm_d)。"""
        self._drift()
        self.defo = max(-8.0, min(8.0, self.defo + self.rate * step_days))
        return self.defo, round(self.rate, 3)

    def force(self, defo):
        """注入指定形变值（超限/回落用）。

        速率仍报当前漂移率，**不**按这一跳反推：注入是人工试验刺激，不是物理漂移，
        按它反推会得到 100+ mm/d 的荒唐速率，把 rate 曲线整条带偏。
        """
        self.defo = defo
        return defo, round(self.rate, 3)

    def message(self, device, clock, quality=None, state="normal", step_days=0.0):
        defo, rate = self.next_values(step_days)
        return self.build(device, clock, defo, rate, quality, state)

    def build(self, device, clock, defo, rate, quality=None, state="normal"):
        self.sequence += 1
        msg = {
            "schemaVersion": "1.0",
            "messageId": str(uuid.uuid4()),
            "deviceId": device,
            "pointCode": self.code,
            "collectTime": clock.isoformat(timespec="milliseconds"),
            "sequence": self.sequence,
            "metrics": {"defo_mm": round(defo, 4), "rate_mm_d": rate},
            "position": {"angleDeg": round(self.rng.uniform(40, 60), 1),
                         "distanceM": round(self.rng.uniform(15, 30), 1)},
            "signal": round(self.rng.uniform(0.7, 1.0), 3),
            "state": state,
        }
        if quality:
            msg["quality"] = quality
        self.last_message = msg
        return msg


def post(url, key, items, timeout):
    """发一批消息，返回 ingest 的 data 段（accepted/rejected/duplicates/results）。"""
    body = json.dumps({"items": items}, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "X-Ingest-Key": key,
    })
    with urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    # 统一信封 {code,message,data}
    if payload.get("code") != 0:
        raise RuntimeError("ingest 返回业务错误: %s" % payload.get("message"))
    return payload["data"]


def overlimit_value(step_index, steps, hold):
    """按「每档保持 hold 轮」的节奏取当前档位；超出档位后取最后一档。"""
    return steps[min(step_index // max(1, hold), len(steps) - 1)]


def main():
    ap = argparse.ArgumentParser(description="雷达数据模拟器（对齐 message-contract）")
    ap.add_argument("--url", default=DEFAULT_URL, help="ingest 端点")
    ap.add_argument("--key", default=DEFAULT_KEY, help="X-Ingest-Key（env MONITOR_INGEST_KEY）")
    ap.add_argument("--device", default=DEFAULT_DEVICE, help="设备码（须在设备档案存在）")
    ap.add_argument("--points", default=",".join(DEFAULT_POINTS),
                    help="逗号分隔的测点业务编码")
    ap.add_argument("--interval", type=float, default=5.0, help="每轮间隔秒数")
    ap.add_argument("--count", type=int, default=0, help="跑几轮；0 = 一直跑（Ctrl-C 停）")
    ap.add_argument("--once", action="store_true", help="只跑一轮（等同 --count 1 --interval 0）")
    ap.add_argument("--step-minutes", type=float, default=30.0,
                    help="每轮代表多少「模拟时间」（写入 collectTime，并用于形变积分）")
    ap.add_argument("--backdate-days", type=float, default=0.0,
                    help="模拟时钟比当前时间往前拨几天；演示历史曲线时用")
    ap.add_argument("--seed", type=int, default=None, help="随机种子（便于复现）")
    ap.add_argument("--timeout", type=float, default=10.0, help="单次请求超时秒数")
    ap.add_argument("--dry-run", action="store_true", help="只打印不发送")

    inj = ap.add_argument_group("注入（验收第 2/3 条）")
    inj.add_argument("--inject-overlimit", action="store_true",
                     help="让 --overlimit-point 的 defo 阶梯上升，跨过触发与升级阈值")
    inj.add_argument("--overlimit-point", default="P-HK01", help="被注入超限的测点")
    inj.add_argument("--overlimit-steps", default="%.1f,%.1f,%.1f" % (WARN_LEVEL + 0.2, 4.2, ALARM_LEVEL + 0.6),
                     help="阶梯形变值，默认 +3.2,+4.2,+5.6（前者触发 warning，后者升到 alarm）")
    inj.add_argument("--overlimit-hold", type=int, default=3, help="每档保持几轮")
    inj.add_argument("--recover-after", type=int, default=0,
                     help="第几轮后回落到 --recover-value（0=不回落）；用于看自动解除")
    inj.add_argument("--recover-value", type=float, default=0.5, help="回落目标形变值")
    inj.add_argument("--inject-duplicate", action="store_true",
                     help="每轮把上一轮的消息原样重发（同 messageId），验幂等")
    inj.add_argument("--inject-suspect", action="store_true",
                     help="超限值改标 quality=SUSPECT，验质量闸门（不产生警情）")
    inj.add_argument("--inject-outage", action="store_true", help="到点后停止上报，模拟断连")
    inj.add_argument("--outage-after", type=int, default=0, help="第几轮后停止上报")
    args = ap.parse_args()

    if args.once:
        args.count, args.interval = 1, 0.0

    codes = [c.strip() for c in args.points.split(",") if c.strip()]
    if not codes:
        print("没有可用的测点码", file=sys.stderr)
        return 2
    if args.inject_overlimit and args.overlimit_point not in codes:
        # 注入点不在点集里等于没注入——这是最容易犯的静默错误，直接报错
        print("--overlimit-point %s 不在 --points 里，超限注入不会生效"
              % args.overlimit_point, file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    sims = {c: PointSim(c, rng) for c in codes}
    steps = [float(x) for x in args.overlimit_steps.split(",") if x.strip()]

    print("模拟器启动：%d 个测点 / 设备 %s / 间隔 %ss%s"
          % (len(codes), args.device, args.interval, "（dry-run，不发送）" if args.dry_run else ""))
    print("端点：%s" % args.url)
    if args.inject_overlimit:
        print("注入超限：%s 阶梯 %s，每档 %d 轮%s"
              % (args.overlimit_point, steps, args.overlimit_hold,
                 "，第 %d 轮后回落到 %.1f" % (args.recover_after, args.recover_value)
                 if args.recover_after else ""))
    if args.inject_duplicate:
        print("注入重复：每轮重发上一轮消息（同 messageId）")
    if args.inject_suspect:
        print("注入可疑：超限值标 quality=SUSPECT（质量闸门应拦下）")
    if args.inject_outage:
        print("注入断连：第 %d 轮后停止上报；设备要静默满 5 分钟（OFFLINE_MINUTES）"
              "才被判离线" % args.outage_after)
    print()

    step_days = args.step_minutes / 1440.0
    # 模拟时钟：默认从「当前」起步，每轮走 --step-minutes；--backdate-days 把它往前拨，
    # 这样跑出来的是一段历史曲线而不是一排未来时间戳。
    clock = datetime.now(TZ) - timedelta(days=args.backdate_days)
    print("模拟时钟：%s 起，每轮 +%g 分钟" % (clock.isoformat(timespec="seconds"), args.step_minutes))

    total = {"accepted": 0, "rejected": 0, "duplicates": 0}
    round_no = 0
    try:
        while args.count == 0 or round_no < args.count:
            round_no += 1
            if args.inject_outage and round_no > args.outage_after:
                print("[%s] 第 %d 轮：已断连，不上报（静默计时中）"
                      % (datetime.now(TZ).strftime("%H:%M:%S"), round_no))
                time.sleep(args.interval)
                continue

            items = []
            for code in codes:
                sim = sims[code]
                if args.inject_overlimit and code == args.overlimit_point:
                    if args.recover_after and round_no > args.recover_after:
                        defo, rate = sim.force(args.recover_value)
                    else:
                        defo, rate = sim.force(overlimit_value(round_no - 1, steps, args.overlimit_hold))
                    # 质量闸门：SUSPECT 的超限值不参与告警判定（message-contract §3）
                    items.append(sim.build(args.device, clock, defo, rate,
                                           quality="SUSPECT" if args.inject_suspect else None,
                                           state="suspicious" if args.inject_suspect else "normal"))
                else:
                    items.append(sim.message(args.device, clock, step_days=step_days))

            stamp = datetime.now(TZ).strftime("%H:%M:%S")
            head = "[%s] 第 %d 轮：%d 条" % (stamp, round_no, len(items))
            if args.inject_overlimit:
                head += "，%s defo=%.2f" % (args.overlimit_point, items[codes.index(args.overlimit_point)]
                                           ["metrics"]["defo_mm"])

            if args.dry_run:
                print(head)
                for it in items:
                    print("    %s defo=%.4f rate=%.3f q=%s"
                          % (it["pointCode"], it["metrics"]["defo_mm"],
                             it["metrics"]["rate_mm_d"], it.get("quality", "(推导)")))
            else:
                try:
                    # 同轮重发一次：messageId 不变 -> 幂等应全部判 DUPLICATE
                    batch = items + (items if args.inject_duplicate else [])
                    data = post(args.url, args.key, batch, args.timeout)
                    for k in total:
                        total[k] += data.get(k, 0)
                    print("%s -> accepted=%d rejected=%d duplicates=%d"
                          % (head, data.get("accepted", 0),
                             data.get("rejected", 0), data.get("duplicates", 0)))
                    for r in data.get("results", []):
                        if r.get("status") != "OK":
                            print("    %s %s %s" % (r.get("pointCode"), r.get("status"),
                                                    r.get("quality") or ""))
                except HTTPError as e:
                    print("%s -> HTTP %s：%s" % (head, e.code, e.read().decode("utf-8", "replace")[:200]),
                          file=sys.stderr)
                    return 1
                except URLError as e:
                    print("%s -> 连不上 %s（后端起了吗？）：%s" % (head, args.url, e.reason),
                          file=sys.stderr)
                    return 1

            clock += timedelta(minutes=args.step_minutes)
            if args.count == 0 or round_no < args.count:
                time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\n收到 Ctrl-C，停止上报。")

    print("\n累计：accepted=%d rejected=%d duplicates=%d"
          % (total["accepted"], total["rejected"], total["duplicates"]))
    if args.inject_outage:
        print("断连已生效：设备需静默满 5 分钟才被判离线，之后 DeviceAlarmMonitor 才会生成设备告警。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
