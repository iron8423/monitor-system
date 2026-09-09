# 通用多传感监测管理系统（monitor-system）

> 给客户用的监测管理系统；首批数据源=毫米波点形变雷达；落地清远电厂灰库/库区边坡。
> 开发基线：`通用多传感监测管理系统_需求分析与开发指引_v1.0.md`；最终方案与分工见 `双人分工实施方案.md`。

## 结构

```text
monitor-system/                 ← GitHub 仓库名
├── .gitattributes / .gitignore
├── README.md
├── docs/                       需求/方案/接口/日志/归档
│   ├── daily/                  每日工作日志（YYYY-MM-DD-A/B.md）
│   ├── 需求分析与开发指引_v1.0.md   开发基线（唯一有效需求）
│   ├── 双人分工实施方案.md        分工与实施（两阶段）
│   ├── message-contract.md      雷达标准消息契约
│   ├── B侧接口契约_M0.md / M0对表清单.md / M0_契约对齐.json
│   └── archive/                需求历史稿（只读，可保留可忽略）
├── backend/                    Spring Boot 后端（B1 ingest 骨架；A 底座待并入）
├── frontend/                   Vue3 + Cesium 前端（阶段 2，占位）
├── tools/
│   └── radar_csv_replay/       真雷达 CSV 回放适配器（Python）
└── .git
```

## 协作约定

- 单仓库 monorepo；main（保护）+ feature/<模块> 分支 + PR。
- 行尾 LF（.gitattributes）；UTF-8；相对路径；每日工作日志当天提交。
- 阶段 1 后端用 H2 内存库；PG/Docker 留阶段 2 统一。

## 当前进度

- M0 契约已冻结（测项 defo_mm/rate_mm_d、幂等 device_id+message_id、±3mm 规则、X-Ingest-Key）。
- B1 ingest 接入骨架已就绪；CSV 回放适配器已对齐。
