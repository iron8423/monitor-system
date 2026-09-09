# monitor-system · 通用监测管理系统

通用多传感监测管理系统 Demo（V0.1 最小闭环）。

- 数据源：毫米波点形变雷达（Demo 阶段走模拟器 + 标准消息）
- 落地：清远电厂灰库 / 库区边坡
- 结构：单仓库 monorepo —— `backend/`（Spring Boot）+ `frontend/`（Vue3，阶段 2）

> 需求口径、分工、里程碑见 `docs/双人分工实施方案v2.md`。

## 目录

```
monitor-system/
├── docs/          # 分工方案、消息契约、每日日志
├── plan/          # 需求与分工方案
├── backend/       # Spring Boot 3 + Java 21 + MyBatis-Plus + Flyway
└── frontend/      # Vue3（阶段 2 由 B 创建）
```

## 启动（阶段 1，后端）

```bash
cd backend
./mvnw spring-boot:run      # Linux/macOS
# Windows: mvnw.cmd spring-boot:run
```

- 默认 H2 内存库（零配置）；阶段 2 切 PostgreSQL + Docker Compose。
- 健康检查：`GET http://localhost:8080/api/v1/health`
- 接口文档：`http://localhost:8080/swagger-ui`

> 完整启动步骤与演示账号在 A5（阶段 2）补齐。
