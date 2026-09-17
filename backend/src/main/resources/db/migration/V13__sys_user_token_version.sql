-- 令牌版本：JWT 带上签发时的版本，鉴权时与库中当前值比对。
--
-- 解决的问题：此前 JwtAuthFilter 只解析令牌里的 claims 就构造登录主体，**全程不查库**，
-- 于是「停用账号」「降权」「改密」在最长 24h（令牌寿命）内都不生效，
-- SecurityUser.isEnabled() 在 JWT 路径上永不参与判断。
--
-- 语义：token_version 递增即表示「此前签发的所有令牌作废」。
--   UPDATE sys_user SET token_version = token_version + 1 WHERE username = 'xxx';
--
-- 注意（升级后果，需写进交付说明）：本次升级后**所有既有令牌立即失效**，用户要重新登录一次
-- ——旧令牌没有 ver claim，鉴权一律拒绝。这是刻意的：若放行旧令牌，这套机制在最长一个
-- 令牌周期内形同不存在。
ALTER TABLE sys_user
    ADD COLUMN token_version INT NOT NULL DEFAULT 0;
