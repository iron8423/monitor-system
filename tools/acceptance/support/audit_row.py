#!/usr/bin/env python3
"""从审计列表里挑出「最新一条符合条件的行」，打印它的 JSON；没有则打印 <无>。

用法（验收套件里调）：
    audit_row.py BASE TOKEN TARGET_TYPE ACTION [TARGET_ID] [USERNAME]

为什么要单独一个脚本：套件里用 `python3 -c` 写多行过滤在 bash 里既难读也难改，
而这个过滤在两个套件里都要用（17-audit 的三处）。宁可多一个文件。

约定：
  · 只取第一页 50 条——套件刚写进去的行一定在最新一端；
  · 参数为 `-` 表示"这一项不过滤"；
  · 找不到时打印 `<无>`，让调用方的断言**在明处失败**，而不是拿到空串装成功。
"""
import json
import sys
import urllib.request


def fetch(base, token, target_type):
    url = f"{base}/audit-logs?pageNum=1&pageSize=50&targetType={target_type}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)["data"]["records"]


def main():
    if len(sys.argv) < 5:
        print("<参数不足：BASE TOKEN TARGET_TYPE ACTION [TARGET_ID] [USERNAME]>")
        return
    base, token, target_type, action = sys.argv[1:5]
    target_id = sys.argv[5] if len(sys.argv) > 5 else "-"
    username = sys.argv[6] if len(sys.argv) > 6 else "-"

    rows = fetch(base, token, target_type)
    for row in rows:
        if row.get("action") != action:
            continue
        if target_id != "-" and str(row.get("targetId")) != target_id:
            continue
        if username != "-" and row.get("username") != username:
            continue
        print(json.dumps(row, ensure_ascii=False))
        return
    print("<无>")


if __name__ == "__main__":
    main()
