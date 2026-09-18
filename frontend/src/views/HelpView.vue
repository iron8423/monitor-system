<script setup>
import { computed } from 'vue'

import { useUserStore } from '@/stores/user'

/**
 * 使用说明（所有角色可见，放在侧边栏最后一项）。
 *
 * 写法上刻意"以当前登录角色为中心"：四个角色的落地页、能看到的菜单、能做的处置动作
 * 都不一样（见 AlarmConstants.ROLE_ACTIONS），一视同仁地列一遍等于让人自己去找。
 * 所以第一块永远是「你是谁 → 你登录后落在哪 → 你能做什么」，其余模块说明再按菜单顺序排。
 *
 * 这里的内容是「界面口径」的复述，不复制后端契约：阈值、判据这类会漂移的值一律说
 * "见告警规则/文档"，避免两处各写一份数字。
 */

defineOptions({ name: 'HelpView' })

const userStore = useUserStore()

const ROLE_INFO = {
  ADMIN: {
    label: '系统管理员',
    home: '/home/admin（管理工作台）',
    duty: '档案与权限、审计、全量数据；四个角色的菜单你都能看到',
    actions: '告警处置的 6 个动作全部可做（确认 / 研判 / 派发 / 处置 / 解除 / 误报）',
  },
  OPERATOR: {
    label: '值班员',
    home: '/home/operator（值班工作台）',
    duty: '盯待确认警情：先确认，再按现场情况派发或处置',
    actions: '确认 / 派发 / 处置 / 解除 / 误报（不能做研判）',
  },
  ANALYST: {
    label: '研判员',
    home: '/home/analyst（研判工作台）',
    duty: '对待研判队列做研判，并对照该测点的形变曲线',
    actions: '研判 / 解除 / 误报（不能确认或派发）',
  },
  MAINTAINER: {
    label: '运维员',
    home: '/home/maintainer（运维工作台）',
    duty: '看设备异常表（离线 / 故障 / 低电）与待处置队列，写维护记录、绑定测点',
    actions: '处置 / 解除 / 误报（不能确认或研判）',
  },
}

const role = computed(() => ROLE_INFO[userStore.role] || null)
const roleRows = Object.entries(ROLE_INFO).map(([key, v]) => ({ key, ...v }))

/** 模块说明：按侧边栏顺序写，标题与菜单名一致，方便对照。 */
const MODULES = [
  {
    name: '工作台',
    path: '/home',
    scope: '所有角色',
    what: '登录后的第一屏：KPI（测点总数 / 未解除警情 / 在线设备 / 最大形变）、测点一览、最近警情。四个角色各自的落地页不同，队列口径按状态机推导。',
    notes: 'KPI 全部取自 GET /projects/{id}/summary，不在前端各算一套；「在线设备」按 5 分钟未上报即离线判定。',
  },
  {
    name: '测点与曲线',
    path: '/points',
    scope: '所有角色',
    what: '左侧测点列表 + 右侧五个页签：资料 / 曲线 / 数据表 / 影像 / 告警。曲线支持测项切换、原始/按小时/按天三种粒度、时间窗切换。',
    notes: '测点档案里的经纬度高程与 3D 场景同源；曲线窗口内没有数据时会说明原因（没上报、还是没落在窗口里），不是"坏了"。',
  },
  {
    name: '设备状态',
    path: '/devices',
    scope: '管理员 / 运维员 / 值班员',
    what: '设备列表与在线状态，点开抽屉可看基本信息、绑定测点、维护记录；运维员可绑定/解绑测点、写维护记录。',
    notes: '状态是「按最近上报时间现算」的（不是档案里那列），所以"档案写着 ONLINE、列表显示离线"是正常的——列表已统一口径。维护记录的「操作人」由后端取当前登录人，客户端传什么都不算。',
  },
  {
    name: '3D 大屏',
    path: '/screen',
    scope: '所有角色',
    what: '整屏三维视图：项目下拉切场景、测点着色与立柱、雷达视场扇面、地面热力图、告警横幅、时间轴回放。',
    notes: '默认是仓库内的离线地形资产（不联网）；顶部状态芯片会明说「数字孪生已加载 / 未配置场景 / 加载失败」三种情况。推送断流 15 秒、正常 60 秒兜底轮询；「未配置场景」的项目只显示底色，测点与曲线照常可用。',
  },
  {
    name: '告警中心',
    path: '/alarms',
    scope: '所有角色',
    what: '警情列表与筛选、警情详情、处置时间线；抽屉里的动作按钮按角色给。',
    notes: '同一测点同一测项「同时只有一条未解除警情」（等级更高时「就地升级」，不是另开一条）；已解除 / 误报 是终态，不能再处置；越权动作由后端返回 403——界面上不显示按钮只是不让你白点。',
  },
  {
    name: '影像挂点',
    path: '/media',
    scope: '管理员 / 运维员 / 研判员',
    what: '按测点上传/浏览无人机影像，测点详情、总览页、大屏浮窗三处共用同一对组件。',
    notes: '删除是「逻辑删除」：库里留行、盘上留文件（可审计可恢复），所以删完再传同名文件不会"复活"旧记录；上传的图片按服务端生成的路径落盘，客户端文件名不参与路径。',
  },
  {
    name: '管理端',
    path: '/admin',
    scope: '仅管理员',
    what: '项目 / 场景 / 对象 / 测点 / 设备 / 测项 / 告警规则 / 用户 八个页签，可增删改查。',
    notes: '在这里「加一行数据」（比如新测项）业务端立刻可见，不需要改前端代码；资源的新增/修改/删除仅管理员；管理端只能改别人的「角色与启用状态」，改不到别人的个人资料（那是本人权利）。',
  },
  {
    name: '审计日志',
    path: '/audit',
    scope: '仅管理员',
    what: '关键写操作的留痕：谁、什么时候、对什么对象、做了什么，以及操作人 IP。',
    notes: '两个筛选条件都是「精确匹配」（不是模糊匹配），输错一个字就是空列表；「目标是空」是正常的——批量类操作没有单一目标 id。',
  },
  {
    name: '系统运维',
    path: '/ops',
    scope: '仅管理员',
    what: '看服务本身：进程与 JVM、数据库与连接池、SSE 连接数、Flyway 迁移清单、关键表行数与测量时间跨度、运行时开关；另有接口文档入口。',
    notes: '「全部只读」：备份、清理、重跑迁移都在命令行做，页面不提供这类按钮。密钥只回显"是否仍是默认值"，不回显内容。行数是物理行数（含逻辑删除的墓碑行），会比界面上看到的多。',
  },
  {
    name: '个人中心',
    path: '/profile',
    scope: '所有角色（入口在右上角头像）',
    what: '维护本人的姓名 / 公司 / 岗位 / 联系电话 / 邮箱，以及修改密码。',
    notes: '账号与角色只读——那是身份与权限，归管理员；改密需要先验原口令，成功后返回新令牌，「其它端立即失效」。目前「没有」忘记密码的自助找回通道。',
  },
]

const CAUTIONS = [
  ['时间口径', '全系统时间一律 ISO8601 带时区（+08:00）。设备采集时间超前平台时间超过 5 分钟会被「整条拒收」（防止时钟错乱污染曲线）。'],
  ['数据范围', '你只能看到自己参与的项目。管理员不受限；没绑定任何项目的设备只有管理员能看到；项目列表为空不是故障，而是权限范围的正常结果。'],
  ['告警判定', '「数据可疑 / 目标失联」这类质量异常的样本「不参与」超限判定——宁可不报，也不拿坏数据报警；样本不足时质量与延迟判据不成立，也不会解除已有警情。'],
  ['逻辑删除', '测点、设备、影像的删除都是「软删除」（库里保留、影像盘上保留文件），目的是可审计、可恢复；因此"删了还在库里"是设计，不是没删掉。'],
  ['前端与权限', '界面上隐藏按钮只是「不让你白点」，真正的边界在后端（@PreAuthorize + 数据范围）。绕过界面直接调接口，越权一样会被 403 拒绝。'],
  ['主题', '顶栏最右侧、登录页右上角、大屏顶栏都有 白天 / 黑夜 切换，选择记在本地；首次访问跟随操作系统设置。'],
  ['数据性质（重要）', '当前仓库里的形变数据是「模拟器灌入的仿真数据」，地形资产由公开 DEM + 卫星影像合成，二者都「不得」作为真实监测结论或工程测量依据。'],
]
</script>

<template>
  <div class="help-page">
    <div class="mk-panel">
      <div class="mk-panel-title">使用说明</div>
      <div class="intro">
        <p>
          本系统是通用多传感监测管理系统：现场数据接入 → 三维数字孪生 → 实时/历史曲线 →
          超限自动告警 → 确认 / 研判 / 派发 / 处置 / 解除全程留痕。
        </p>
        <p class="mk-muted">
          不同角色看到的菜单与可做的动作不同，下面第一块先说你的角色，其后按侧边栏顺序逐个介绍模块。
        </p>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">
        你的角色
        <span class="mk-spacer" />
        <el-tag size="small" effect="plain">{{ userStore.displayName }} · {{ userStore.roleLabel }}</el-tag>
      </div>
      <div v-if="role" class="role-box">
        <div class="role-line"><span class="k">角色</span><span>{{ role.label }}</span></div>
        <div class="role-line"><span class="k">登录后落在</span><span class="mk-mono">{{ role.home }}</span></div>
        <div class="role-line"><span class="k">主要职责</span><span>{{ role.duty }}</span></div>
        <div class="role-line"><span class="k">可做的处置动作</span><span>{{ role.actions }}</span></div>
      </div>
      <el-table :data="roleRows" size="small" class="mt">
        <el-table-column prop="label" label="角色" width="120">
          <template #default="{ row }">
            <span :class="{ me: row.key === userStore.role }">{{ row.label }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="home" label="落地页" min-width="200" />
        <el-table-column prop="duty" label="主要职责" min-width="300" show-overflow-tooltip />
      </el-table>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">模块一览（按侧边栏顺序）</div>
      <div class="module-list">
        <div v-for="m in MODULES" :key="m.name" class="module">
          <div class="module-head">
            <span class="module-name">{{ m.name }}</span>
            <span class="mk-mono module-path">{{ m.path }}</span>
            <el-tag size="small" effect="plain" type="info">{{ m.scope }}</el-tag>
          </div>
          <div class="module-body">
            <p class="what">{{ m.what }}</p>
            <p class="note"><span class="note-tag">注意</span>{{ m.notes }}</p>
          </div>
        </div>
      </div>
    </div>

    <div class="mk-panel">
      <div class="mk-panel-title">常见口径与注意事项</div>
      <div class="cautions">
        <div v-for="[title, text] in CAUTIONS" :key="title" class="caution">
          <span class="caution-title">{{ title }}</span>
          <span class="caution-text">{{ text }}</span>
        </div>
      </div>
      <div class="mk-footnote">
        更细的口径与验收证据见仓库文档：<span class="mk-mono">docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md</span>（需求基线）、
        <span class="mk-mono">docs/message-contract.md</span>（数据接入契约）、
        <span class="mk-mono">docs/B侧接口契约_M0.md</span>（接口口径）。
      </div>
    </div>
  </div>
</template>

<style scoped>
.help-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.intro {
  padding: 14px 16px;
  font-size: 14px;
  line-height: 1.9;
}

.intro p {
  margin: 0;
}

.role-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px 6px;
}

.role-line {
  display: flex;
  gap: 12px;
  font-size: 13px;
}

.role-line .k {
  flex: 0 0 96px;
  color: var(--mk-text-sub);
}

.mt {
  margin-top: 6px;
}

.me {
  font-weight: 600;
  color: var(--mk-primary);
}

.module-list {
  display: flex;
  flex-direction: column;
}

.module {
  padding: 12px 16px;
  border-bottom: 1px solid var(--mk-border);
}

.module:last-child {
  border-bottom: none;
}

.module-head {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 6px;
}

.module-name {
  font-size: 14px;
  font-weight: 600;
}

.module-path {
  font-size: 12px;
  color: var(--mk-text-sub);
}

.module-body p {
  margin: 0 0 4px;
  font-size: 13px;
  line-height: 1.8;
}

.note {
  color: var(--mk-text-sub);
}

.note-tag {
  display: inline-block;
  padding: 0 6px;
  margin-right: 6px;
  font-size: 12px;
  color: var(--mk-level-warning);
  border: 1px solid var(--mk-level-warning);
  border-radius: 4px;
}

.cautions {
  display: flex;
  flex-direction: column;
}

.caution {
  display: flex;
  gap: 12px;
  padding: 10px 16px;
  font-size: 13px;
  line-height: 1.8;
  border-bottom: 1px solid var(--mk-border);
}

.caution:last-child {
  border-bottom: none;
}

.caution-title {
  flex: 0 0 120px;
  font-weight: 600;
}

.caution-text {
  flex: 1;
  color: var(--mk-text-sub);
}
</style>
