# 给 A 的同步协作说明（monitor-system 仓库）

> 仓库：https://github.com/iron8423/monitor-system.git（私有）· 主分支 `main`
> 我是 B（角色B），已建好仓库并放入：需求基线 v1.0、B1 ingest 骨架、CSV 回放工具、工作日志、接口/消息契约。
> 目标：A 把已有的 backend（底座+schema）并进来，两人共用一份代码。

## 一、你（A）怎么加入并同步

1. **把你的 GitHub 用户名发我（B）** → 我在仓库 **Settings → Collaborators → Add people** 加上你（加完你才有 push 权限）。
2. 在你机器上：
   ```powershell
   git clone https://github.com/iron8423/monitor-system.git
   cd monitor-system
   ```
3. **把你已有的 backend 代码放进 `backend/`**（只放源码：`src/`、`pom.xml`、`application.yml` 等；别放 `target/`、`.idea/`、`*.iml`，`.gitignore` 已忽略）。
4. 提交 + 推送：
   ```powershell
   git add -A
   git commit -m "feat(backend): A 底座 + schema + 档案/auth 接口"
   git push origin main
   ```

> 如果你本地已有一个 backend 仓库（历史独立），**别用 `git push --force` 覆盖**；推荐“clone 后把源码拷进 `backend/` 再提交”，避免两段历史打架。

## 二、日常协作命令

```powershell
git pull                                  # 先拿对方改动
git add -A
git commit -m "feat(模块): 说明"
git push                                  # 再推自己的
```

- **先 pull 再 push**，避免冲突；若有冲突，解决后 `git add -A && git commit && git push`。
- 约定：功能用 `feature/<模块>` 分支，稳定后合 `main`；两人可先在 `main` 上、但务必先 pull 再 push。
- **工作日志**：`docs/daily/YYYY-MM-DD-A.md`（A）/ `YYYY-MM-DD-B.md`（B），当天提交（模板见方案 §5.5）。

## 三、环境/约定（已由 A 前面对齐）

- `.gitattributes` 已配 LF：Java/YAML/MD/JS 等用 LF；Windows 用 `mvnw.cmd`、Linux 用 `./mvnw`；开发库用 H2。
- 鉴权：ingest 用请求头 `X-Ingest-Key: dev-ingest-key`（读 `MONITOR_INGEST_KEY`）；stream 用 `?token=<JWT>`。
- 幂等：`device_id + message_id`；默认告警规则：`defo_mm ±3mm 触发 / ±1mm 恢复 / warning`。

## 四、待你（A）提供/确认

- 你的 **GitHub 用户名**（好加 collaborator）。
- **`measurement` 表列名**（我据此对齐 B1 的 `Measurement.java`）。
- `alarm_rule` 扩展列是否已按 D6 落地（`point_id/metric_code/rule_type/operator/threshold_value/window_minutes/recovery_value/level/repeat_suppress_seconds/enabled`）。
