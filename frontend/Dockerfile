# ---------------------------------------------------------------------------
# 前端镜像（对应验收脚本第 8 条的后半句「浏览器完整可访问」）
#
# 两阶段是刻意的：
#   build   —— 有 Node，负责 npm ci + vite build
#   runtime —— 只有 nginx，托管 dist/ 并把 /api 反代到 backend:8080
#
# 依赖层单独缓存：package.json / package-lock.json 先于 src/ 拷入，
# 所以改页面代码不会重装依赖，只有依赖变了才重装。
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Cesium 的 ion token 是**构建期**变量：Vite 把 VITE_* 内联进产物，不是运行时读的。
# 所以它只能走 build arg，换 token 必须重建镜像（docker compose build frontend）。
# 不传也能构建——3D 大屏会走「无地形兜底底图」的降级路径（已实测，不白屏）。
# 另外三个 VITE_*（API 前缀、应用标题、后端 origin）代码里都有正确兜底，不必传。
ARG VITE_CESIUM_ION_TOKEN=
ARG VITE_TERRAIN_MODE=ion
ARG VITE_SCENE_MODE=mountain
ENV VITE_SCENE_MODE=$VITE_SCENE_MODE \
    VITE_CESIUM_ION_TOKEN=$VITE_CESIUM_ION_TOKEN \
    VITE_TERRAIN_MODE=$VITE_TERRAIN_MODE

# scripts/ 要跟 package.json 一起先拷进来：npm ci 结束时会跑 postinstall
# （scripts/copy-cesium.mjs），它把 Cesium 的 Workers/Assets/Widgets/ThirdParty
# 从 node_modules/cesium 复制到 public/cesium/。这几样不是 JS 模块，打包器不会
# 自动带上，缺了它们大屏能开但瓦片解算不出来。
COPY package.json package-lock.json ./
COPY scripts ./scripts
RUN npm ci

# .dockerignore 排除了 public/cesium/ 与 node_modules/，所以这里不会把宿主机那份
# 覆盖回去——上一步在容器内生成的那份是唯一来源。
COPY . .
RUN npm run build

# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# nginx 官方镜像的 entrypoint 会读 /etc/nginx/templates/*.template 做 envsubst，
# 这里用不上（配置是静态的），走默认即可。
EXPOSE 80
