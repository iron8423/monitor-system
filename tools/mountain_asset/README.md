# 离线低多边形山地资产生成器

在项目根目录运行：

```bash
python3 tools/mountain_asset/generate_mountain_glb.py
```

脚本仅使用 Python 标准库，输出：

- `frontend/public/models/mountain-demo/mountain-demo.glb`
- `frontend/public/models/mountain-demo/scene-config.json`
- `frontend/public/models/mountain-demo/points.json`
- `frontend/public/models/mountain-demo/coverage.json`

地形尺寸约 320m × 240m，由固定数学函数生成，包含复合山脊、沟谷、滑坡后缘、滑坡槽、
坡脚堆积体、道路与排水带。160 × 120 个网格单元共生成 38,400 个平面着色三角形。
同一份源码重复执行会得到相同几何，不依赖 Blender、在线服务或第三方模型。

生成器同时定义北、南两台雷达，并对每条雷达—测点关系计算方位角、俯仰角、斜距和沿线
最小净空。目标不在量程/FOV 内或被地形遮挡时，生成过程会直接失败，避免把不合理绑定写入系统。
`coverage.json` 是这组几何计算的机器可读验收结果。
