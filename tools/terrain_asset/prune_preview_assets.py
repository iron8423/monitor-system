"""清理 `frontend/public/models/` 里的**预览资产**（默认 dry-run，不动文件）。

背景（2026-09-22 审计）：模型目录一度涨到 170 MB，其中只有十几 MB 是被 Flyway 迁移
登记的场景资产，其余都是观感试验留下的版本（wide / g19 / hires / powerplant …）。
它们有两个问题：一是体积（单个 30 MB 级），二是**纹理来自 Google/高德影像，
服务条款禁止再分发**，一旦误提交就等于把受限影像推上了共享仓库。

判据是"有没有被迁移登记"：`asset_url` 出现在 `db/migration/*.sql` 里的目录视为正式资产，
一律保留；其余按预览资产处理，移动到仓库外的归档目录（可恢复，不删除）。

用法：
    # 1) 先看会动哪些（默认）
    python tools/terrain_asset/prune_preview_assets.py
    # 2) 真的移动
    python tools/terrain_asset/prune_preview_assets.py --apply --archive D:\\monitor-archive
"""

import argparse
import re
import shutil
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

PROJECT = Path(__file__).resolve().parents[2]
MODELS = PROJECT / "frontend" / "public" / "models"
MIGRATIONS = PROJECT / "backend" / "src" / "main" / "resources" / "db" / "migration"

# 预览资产里"当前在用/需要回滚"的白名单：默认保留，避免一跑就把正在演示的场景搬走
DEFAULT_KEEP = {"qingyuan-powerplant-big", "qingyuan-powerplant-clean"}


def registered_assets() -> set[str]:
    """从迁移里读出所有被登记的模型目录名（asset_url 里的第一段目录）。"""
    names: set[str] = set()
    for path in MIGRATIONS.glob("*.sql"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(r"/models/([\w.-]+)/", text):
            names.add(match.group(1))
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description="清理预览资产（默认 dry-run）")
    parser.add_argument("--apply", action="store_true", help="真的移动到归档目录")
    parser.add_argument("--archive", type=Path, default=PROJECT.parent / "monitor-archive",
                        help="归档根目录（默认在仓库外）")
    parser.add_argument("--keep", default=",".join(sorted(DEFAULT_KEEP)),
                        help="额外保留的目录名，逗号分隔")
    args = parser.parse_args()

    keep = {item.strip() for item in args.keep.split(",") if item.strip()}
    registered = registered_assets()
    print(f"迁移登记的场景资产（保留）：{sorted(registered)}")
    print(f"显式保留的预览资产：{sorted(keep)}\n")

    total = 0
    for path in sorted(p for p in MODELS.iterdir() if p.is_dir()):
        if path.name in registered or path.name in keep:
            continue
        size = sum(f.stat().st_size for f in path.rglob("*") if f.is_file())
        total += size
        action = "移动" if args.apply else "将移动"
        print(f"  {action} {path.name:38s} {size / 1e6:7.1f} MB")
        if args.apply:
            target = args.archive / "models" / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                print(f"    ! 目标已存在，跳过：{target}")
                continue
            shutil.move(str(path), str(target))

    # 远景瓦片同理：Google 影像，不入库
    farfield = PROJECT / "frontend" / "public" / "farfield"
    if farfield.exists():
        size = sum(f.stat().st_size for f in farfield.rglob("*") if f.is_file())
        print(f"\n远景层 {farfield.name}: {size / 1e6:.1f} MB（Google 影像，保持不入库；"
              f"需要归档请手动处理）")

    print(f"\n合计可清理 {total / 1e6:.1f} MB" + ("（已移动）" if args.apply else "（dry-run，未改动）"))
    if not args.apply:
        print("确认后加 --apply 执行。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
