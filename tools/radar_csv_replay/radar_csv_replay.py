# -*- coding: utf-8 -*-
"""
雷达 CSV 回放适配器（角色 B 第一步工具）

作用：读取真雷达导出的 CSV（目标信号强度-YYYYMMDD/TargetN-RSSI-YYYYMMDD.csv），
      按《雷达标准消息契约_v1》把每条记录转成标准消息，按时间顺序回放。
      默认 dry-run（只在控制台打印消息），加 --send 才真正 POST 到系统 ingest 接口。

用法示例：
  python radar_csv_replay.py --date 20260908            # 先 dry-run 看某天数据
  python radar_csv_replay.py --date 20260908 --send --speed 2   # 回放某天到 ingest
  python radar_csv_replay.py --date 20260908 --inject-overlimit --send   # 注入超限测告警
  python radar_csv_replay.py --date 20260908 --inject-outage --send      # 模拟断连/离线
  python radar_csv_replay.py --date 20260908 --inject-duplicate --send   # 测幂等去重

字段映射（CSV -> 标准消息）：
  时间          -> collectTime（与文件夹日期拼 ISO8601，+08:00）
  信号强度      -> signal
  X坐标(°)      -> position.angleDeg
  Y坐标(m)      -> position.distanceM
  状态          -> state
  监测          -> monitored（仅当监测=1 且有累积形变，才作为有效测量）
  累积形变      -> metrics.defo_mm（点形变主指标）
  速率          -> metrics.rate_mm_d（由同一测点相邻两条 defo / 时间差推算）
"""
import argparse, csv, os, re, time, uuid, json, sys, math
from datetime import datetime, timedelta
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

# 真雷达 CSV 的根目录。**刻意不给默认值**（复查清单 P2-7）：
# 旧默认值写死在某台机器的 Windows 桌面路径上，换一台机器运行时它只会表现为
# "没找到 CSV"——看起来像数据没了，其实是路径根本不存在。
# 现在必须显式给：命令行 --root，或环境变量 RADAR_CSV_ROOT。
RADAR_CSV_ROOT_ENV = "RADAR_CSV_ROOT"
DEFAULT_EP = "http://127.0.0.1:8080/api/v1/ingest/measurements"
DEFAULT_INGEST_KEY = "dev-ingest-key"
# V11 起 radar-001 只覆盖北侧可见目标。南侧的 P-HK01/P-BP03/P-BP04
# 必须由 radar-002 上报，不能为了兼容旧演示映射绕过物理覆盖关系。
DEFAULT_POINT_MAP = "1:P-HK02,2:P-HK03,3:P-BP01,4:P-BP02"
FOLDER_RE = re.compile(r"^目标信号强度-(\d{8})$")
TARGET_RE = re.compile(r"^Target(\d+)")


def collect_ts_from_row(folder_date, tstr):
    """CSV 时间是 HH:MM:SS.mmm，拼上文件夹日期成 ISO8601（+08:00）。"""
    # 兼容 17:09:02.582 / 17:09:02 / 17:09:02.582123
    t = tstr.strip().strip('"').strip("'")
    try:
        # 去掉毫秒统一到微秒，用 datetime strptime 兜底
        t = t.rstrip("Z")
        dt = datetime.strptime(t, "%H:%M:%S.%f") if "." in t else datetime.strptime(t, "%H:%M:%S")
    except Exception:
        dt = datetime.strptime(t, "%H:%M:%S")
    full = datetime.combine(folder_date.date(), dt.time())
    return full


def num(v):
    """转 float，失败/空返回 None。"""
    v = (v or "").strip().strip('"').strip("'")
    if not v:
        return None
    try:
        return float(v)
    except Exception:
        return None


def scan_files(root, date_filter=None):
    """返回 [(date, target_n, filepath)]，按日期+目标排序。"""
    out = []
    if not os.path.isdir(root):
        print(f"[!] 目录不存在：{root}")
        return out
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name)
        if not os.path.isdir(p):
            continue
        m = FOLDER_RE.match(name)
        if not m:
            continue
        ymd = m.group(1)
        if date_filter and ymd != date_filter:
            continue
        try:
            d = datetime.strptime(ymd, "%Y%m%d")
        except Exception:
            continue
        for f in sorted(os.listdir(p)):
            if not f.lower().endswith(".csv"):
                continue
            tm = TARGET_RE.match(f)
            if not tm:
                continue
            out.append((d, int(tm.group(1)), os.path.join(p, f)))
    return out


def parse_target_csv(fp, date, point_prefix, device, point_map=None, include_unmonitored=False):
    """解析某个 TargetN 的时间序列 -> 事件列表（含派生 rate）。"""
    rows = []
    try:
        with open(fp, "r", encoding="utf-8-sig", errors="replace") as fh:
            reader = csv.DictReader(fh)
            for row in reader:
                rows.append(row)
    except Exception as e:
        print(f"[!] 读取失败 {fp}: {e}")
        return []

    # 目标序号
    tm = TARGET_RE.match(os.path.basename(fp))
    n_str = tm.group(1) if tm else "0"
    n_int = int(n_str)
    point_code = (point_map.get(n_int) if point_map else None) or f"{point_prefix}{n_str}"

    events = []
    seq = 0
    prev_defo = None
    prev_time = None
    for row in rows:
        tstr = (row.get("时间") or "").strip()
        if not tstr:
            continue
        try:
            ct = collect_ts_from_row(date, tstr)
        except Exception:
            continue
        sig = num(row.get("信号强度"))
        ang = num(row.get("X坐标(°)"))
        dist = num(row.get("Y坐标(m)"))
        state_raw = (row.get("状态") or "").strip()
        monitored = (row.get("监测") or "").strip()
        defo = num(row.get("累积形变"))

        state = "normal" if state_raw == "1" else "suspicious"
        is_mon = (monitored == "1" and defo is not None)
        # 默认只回放真正有“累积形变”的监测行；否则数据全是空形变的“扫到目标”噪声
        if not (is_mon or include_unmonitored):
            continue

        seq += 1
        metrics = {}
        if defo is not None:
            metrics["defo_mm"] = round(defo, 4)
            if prev_defo is not None and prev_time is not None and ct > prev_time:
                dt_h = (ct - prev_time).total_seconds() / 3600.0
                if dt_h > 0:
                    metrics["rate_mm_d"] = round((defo - prev_defo) / (dt_h / 24.0), 4)
            prev_defo = defo
            prev_time = ct

        quality = "VALID"
        if sig is not None and sig < 0.3:
            quality = "SUSPECT"
        if not metrics:
            quality = "SUSPECT"

        events.append({
            "collectTime": ct.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "+08:00",
            "pointCode": point_code,
            "deviceId": device,
            "sequence": seq,
            "metrics": metrics,
            "position": {"angleDeg": (ang if ang is not None else 0.0), "distanceM": (dist if dist is not None else 0.0)},
            "signal": (sig if sig is not None else 0.0),
            "state": state,
            "monitored": is_mon,
            "quality": quality,
        })
    return events


def build_message(ev, base):
    """标准化出一条标准消息 JSON。messageId 用确定性 uuid5，便于幂等。"""
    key = f"{ev['deviceId']}|{ev['pointCode']}|{ev['collectTime']}|{ev['sequence']}"
    return {
        "schemaVersion": "1.0",
        "messageId": str(uuid.uuid5(uuid.NAMESPACE_URL, key)),
        "deviceId": ev["deviceId"],
        "pointCode": ev["pointCode"],
        "collectTime": ev["collectTime"],
        "receiveTime": datetime.now().strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "+08:00",
        "sequence": ev["sequence"],
        "metrics": ev["metrics"],
        "quality": ev["quality"],
        "position": ev["position"],
        "signal": ev["signal"],
        "state": ev["state"],
        "attributes": {"monitored": (1 if ev["monitored"] else 0), "from": "csv-replay", "source": base},
    }


def post(ep, payload, ingest_key=None):
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if ingest_key:
        headers["X-Ingest-Key"] = ingest_key
    req = Request(ep, data=data, headers=headers, method="POST")
    try:
        with urlopen(req, timeout=5) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except URLError as e:
        return 0, f"URL 错误：{e.reason}"
    except Exception as e:
        return -1, str(e)


def main():
    ap = argparse.ArgumentParser(description="雷达 CSV 回放适配器")
    ap.add_argument("--root", default=None,
                    help="真雷达 CSV 根目录（必填，或用环境变量 RADAR_CSV_ROOT）")
    ap.add_argument("--date", default=None, help="只回放某天，格式 YYYYMMDD，如 20260908")
    ap.add_argument("--target", default=None, help="只回放某个目标，如 1（对应 Target1）")
    ap.add_argument("--endpoint", default=DEFAULT_EP, help="系统 ingest 接口")
    ap.add_argument("--send", action="store_true", help="真正 POST；不加则 dry-run 只打印")
    ap.add_argument("--speed", type=float, default=1.0, help="每秒回放条数（默认 1）")
    ap.add_argument("--max-events", type=int, default=0, help="最多回放条数（0=不限）")
    ap.add_argument("--device", default="radar-001", help="设备 ID")
    ap.add_argument("--point-prefix", default="TARGET-", help="测点编码前缀，默认 TARGET-")
    ap.add_argument("--point-map", default=DEFAULT_POINT_MAP, help="雷达目标->档案点号映射，如 1:P-HK01,2:P-HK02")
    ap.add_argument("--ingest-key", default=None, help="X-Ingest-Key 值；默认读环境变量 MONITOR_INGEST_KEY，否则占位 dev-ingest-key")
    ap.add_argument("--include-unmonitored", action="store_true", help="把监测=0、无累积形变的空数据行也回放（默认只放有变形值的监测行）")
    ap.add_argument("--ingest-mode", choices=("REALTIME", "BACKFILL"), default="BACKFILL",
                    help="接入模式；CSV 历史导入默认 BACKFILL，不影响当前告警、在线状态和 SSE")
    ap.add_argument("--inject-overlimit", action="store_true", help="把部分 defo 放大，制造超限")
    ap.add_argument("--over-target", type=float, default=4.0, help="注入超限的目标值(mm)，默认 4.0（对齐 ±3 触发）")
    ap.add_argument("--inject-outage", action="store_true", help="掐掉中间一段时间，模拟断连/离线")
    ap.add_argument("--inject-duplicate", action="store_true", help="每条消息发两遍，测幂等去重")
    args = ap.parse_args()

    # 根目录必须显式给（P2-7）：没给就在**开始之前**把话说清楚，
    # 而不是跑到 scan_files 里一路空转到"没找到 CSV"——那会让人以为数据坏了。
    root = args.root or os.environ.get(RADAR_CSV_ROOT_ENV)
    if not root:
        print(
            "[!] 未指定 CSV 根目录。请用 --root <目录>，或设置环境变量 "
            f"{RADAR_CSV_ROOT_ENV}；目录下应当有形如「目标信号强度-YYYYMMDD」的子目录。\n"
            "    例：python radar_csv_replay.py --root /data/radar/web_存档 --date 20260908",
            file=sys.stderr,
        )
        sys.exit(2)
    if not os.path.isdir(root):
        print(f"[!] 目录不存在：{root}", file=sys.stderr)
        sys.exit(2)

    point_map = {}
    for seg in (args.point_map or "").split(","):
        if ":" in seg:
            k, v = seg.split(":", 1)
            point_map[int(k.strip())] = v.strip()
    ingest_key = args.ingest_key or os.environ.get("MONITOR_INGEST_KEY") or DEFAULT_INGEST_KEY

    files = scan_files(root, args.date)
    if not files:
        print(f"[!] 在 {root} 下没找到符合 --date 的 CSV。请确认 --root 与 --date。")
        sys.exit(1)

    all_events = []
    for d, n, fp in files:
        if args.target is not None and n != int(args.target):
            continue
        base = os.path.basename(fp)
        all_events.extend(parse_target_csv(fp, d, args.point_prefix, args.device, point_map, args.include_unmonitored))

    # 按时间排序，回放更真实
    all_events.sort(key=lambda e: e["collectTime"])
    print(f"[i] 命中文件 {len(files)} 个，解析出事件 {len(all_events)} 条。")
    if not all_events:
        print("[!] 没有可回放的有效事件（大多可能监测=0 或无累积形变）。")
        sys.exit(2)

    # 注入：断连 → 掐掉中间 1/3 时间窗
    if args.inject_outage and len(all_events) > 4:
        cut_start = all_events[len(all_events)//3]["collectTime"]
        cut_end = all_events[len(all_events)*2//3]["collectTime"]
        before = len(all_events)
        all_events = [e for e in all_events if not (cut_start <= e["collectTime"] <= cut_end)]
        print(f"[i] 注入断连：丢弃 {before - len(all_events)} 条（{cut_start} ~ {cut_end}）。")

    # 注入：超限 → 部分 defo 放大
    if args.inject_overlimit:
        t = args.over_target
        for i, e in enumerate(all_events):
            if "defo_mm" in e["metrics"] and i % 3 == 0:
                orig = e["metrics"]["defo_mm"]
                sign = 1 if orig >= 0 else -1
                e["metrics"]["defo_mm"] = round(sign * t, 4)
        print(f"[i] 注入超限：每隔 3 条 defo 设为 ±{t} mm（对齐 ±3 触发 / 恢复 ±1，warning ）。")

    count = 0
    total = len(all_events)
    for e in all_events:
        if args.max_events and count >= args.max_events:
            break
        msg = build_message(e, "radar-csv")
        times = [msg] if args.inject_duplicate else [msg]
        for m in times:
            count += 1
            if args.send:
                # B1 的 IngestRequest 只接受批量 { "items": [ ...] }；每条消息包一层
                code, resp = post(args.endpoint,
                                  {"ingestMode": args.ingest_mode, "items": [m]}, ingest_key)
                if code not in (200, 201, 202):
                    print(f"    [x] HTTP {code} {m['pointCode']} {m['collectTime']} -> {resp[:120]}")
                elif count % 20 == 0:
                    print(f"    [.] 已发 {count}/{total}，最近点 {m['pointCode']} defo={m['metrics'].get('defo_mm')}")
            else:
                print(json.dumps(m, ensure_ascii=False))
        if args.send and args.speed > 0:
            time.sleep(1.0 / args.speed)

    if args.send:
        print(f"[√] 回放结束，共发送 {count} 条 -> {args.endpoint}")
    else:
        print(f"[i] dry-run 结束（共 {count} 条）。加 --send 才会真正 POST 到系统。")


if __name__ == "__main__":
    main()
