#!/usr/bin/env python3
"""数出「某个登录用户在项目 1 里可见的测点数」。

10-scope.sh ④ 用它来对账：项目概览的 pointCount 与测点列表两条路径必须同口径。
2026-09-18（V20）之后，非管理员同时属于项目 1/3/4/5，直接数 `/points` 得到的是
"她全部可见的点"，与"项目 1 的点"不是一回事，所以这里显式沿
project → scene → object → point 这条链走。

用法：project1_point_count.py <BASE> <TOKEN>
"""

from __future__ import annotations

import json
import sys
import urllib.request


def main() -> int:
    base, token = sys.argv[1], sys.argv[2]

    def get(path: str):
        request = urllib.request.Request(base + path,
                                         headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)["data"]

    scene_ids = {s["id"] for s in get("/scenes") if s.get("projectId") == 1}
    object_ids = {o["id"] for o in get("/objects") if o.get("sceneId") in scene_ids}
    print(len([p for p in get("/points") if p.get("objectId") in object_ids]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
