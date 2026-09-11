/**
 * 把 Cesium 的运行时静态资源从 node_modules 复制到 public/cesium/。
 *
 * 为什么需要：Cesium 运行时要去 /cesium/ 下找 Workers（多线程解算瓦片）、
 * Assets（内置贴图/图标）、Widgets（控件样式）、ThirdParty。这几样不是 JS 模块，
 * 打包器不会自动带上，必须作为静态文件提供。
 *
 * 由 package.json 的 postinstall 自动执行（npm install 之后就位），也可手动跑：
 *   npm run cesium:assets
 */
import { cp, mkdir, rm, stat } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(here, '..')
const source = join(projectRoot, 'node_modules', 'cesium', 'Build', 'Cesium')
const target = join(projectRoot, 'public', 'cesium')
const DIRS = ['Workers', 'Assets', 'Widgets', 'ThirdParty']

async function exists(p) {
  try {
    await stat(p)
    return true
  } catch {
    return false
  }
}

async function main() {
  if (!(await exists(source))) {
    console.error(`[cesium] 找不到 ${source}\n        先执行 npm install 安装 cesium 依赖。`)
    process.exitCode = 1
    return
  }

  await mkdir(target, { recursive: true })
  for (const dir of DIRS) {
    const from = join(source, dir)
    const to = join(target, dir)
    if (!(await exists(from))) continue
    await rm(to, { recursive: true, force: true })
    await cp(from, to, { recursive: true })
    console.log(`[cesium] ${dir} -> public/cesium/${dir}`)
  }
  console.log('[cesium] 静态资源就绪：public/cesium/')
}

await main()
