#!/usr/bin/env python3
"""打印"相对现在偏移若干天/小时/分钟"的 ISO8601 时间（带 +08:00），可选 URL 编码。

用法：
    ts_offset.py --days -40
    ts_offset.py --hours -2 --urlencode
    ts_offset.py --minutes 5

为什么要一个脚本：验收套件里到处要用这种时间戳，而 bash 里塞多行 python 既难读、
又容易在复制粘贴时丢掉续行符（本仓已经因此踩过两次）。参数固定成三个是刻意的——
需要更复杂的时间运算时，请另写一个 support 脚本，而不是在这里堆参数。
"""
import argparse
import datetime
import urllib.parse

TZ = datetime.timezone(datetime.timedelta(hours=8))


def main() -> int:
    ap = argparse.ArgumentParser(description="相对现在的 ISO8601 时间（Asia/Shanghai）")
    ap.add_argument("--days", type=float, default=0)
    ap.add_argument("--hours", type=float, default=0)
    ap.add_argument("--minutes", type=float, default=0)
    ap.add_argument("--urlencode", action="store_true", help="输出 URL 编码后的形式（可直接拼进 query）")
    args = ap.parse_args()

    now = datetime.datetime.now(TZ)
    stamp = now + datetime.timedelta(days=args.days, hours=args.hours, minutes=args.minutes)
    text = stamp.isoformat(timespec="seconds")
    print(urllib.parse.quote(text) if args.urlencode else text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
