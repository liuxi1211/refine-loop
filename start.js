#!/usr/bin/env node
'use strict'

/**
 * refine-loop 本地开发启动脚本（零依赖，Node 18+）
 *
 * 用法：
 *   node start.js               前后端一起启动（默认 all）
 *   node start.js all           同上：先编译/启动后端，等 /api/health 就绪后再起前端
 *   node start.js backend       只启动后端（源码比 jar 新时自动重新编译）
 *   node start.js frontend      只启动前端（缺 node_modules 时自动 npm install）
 *   node start.js build         只编译/打包后端（跳过测试）
 *   node start.js build --test  只编译后端并跑全量测试
 *
 * 可选参数：
 *   --build   强制重新编译后端，忽略增量判断
 *
 * 环境变量：
 *   BACKEND_PROFILE  后端 profile，默认 dev
 *   JAVA_HOME        指定 JDK 目录（默认使用 PATH 中的 java）
 */

const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const http = require('node:http')
const { spawn, spawnSync } = require('node:child_process')

const ROOT = __dirname
const FRONTEND_DIR = path.join(ROOT, 'frontend')
const TARGET_DIR = path.join(ROOT, 'target')

const IS_WIN = process.platform === 'win32'
const BACKEND_PORT = 8080
const FRONTEND_PORT = 5173
const BACKEND_PROFILE = process.env.BACKEND_PROFILE || 'dev'
const HEALTH_URL = `http://127.0.0.1:${BACKEND_PORT}/api/health`
const HEALTH_TIMEOUT_MS = 180_000

const C = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m'
}

const children = []
let shuttingDown = false
let mvnCache

// ---------------------------------------------------------------- 基础工具

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function log(msg) {
  console.log(msg)
}

function tag(name, color) {
  return `${color}[${name}]${C.reset}`
}

function safeReaddir(dir) {
  try {
    return fs.readdirSync(dir)
  } catch {
    return []
  }
}

function isFile(p) {
  try {
    return fs.statSync(p).isFile()
  } catch {
    return false
  }
}

/** 命令行 token 转义：含空格/引号时按平台加引号 */
function q(token) {
  if (!/[\s"']/.test(token)) return token
  return IS_WIN ? `"${token}"` : `'${token.replace(/'/g, `'\\''`)}'`
}

function buildCmd(parts) {
  return parts.map(q).join(' ')
}

// ---------------------------------------------------------------- 子进程

function pipeWithPrefix(child, name, color) {
  const forward = (stream, isStderr) => {
    let buffer = ''
    stream.setEncoding('utf8')
    stream.on('data', (chunk) => {
      buffer += chunk
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop()
      for (const line of lines) {
        process[isStderr ? 'stderr' : 'stdout'].write(`${tag(name, color)} ${line}\n`)
      }
    })
  }
  if (child.stdout) forward(child.stdout, false)
  if (child.stderr) forward(child.stderr, true)
}

/**
 * 启动一个长驻子进程。Windows 走 cmd.exe（/s /c + 原样参数），
 * 以便正确执行 mvn.cmd / npm.cmd 这类批处理包装器。
 */
function spawnLine(name, color, commandLine, cwd) {
  const env = { ...process.env, FORCE_COLOR: '1' }
  const child = IS_WIN
    ? spawn('cmd.exe', ['/d', '/s', '/c', commandLine], {
        cwd,
        env,
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsVerbatimArguments: true
      })
    : spawn('sh', ['-c', commandLine], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })

  pipeWithPrefix(child, name, color)
  child.on('error', (err) => {
    log(`${tag(name, color)} ${C.red}启动失败：${err.message}${C.reset}`)
  })
  children.push(child)
  return child
}

/** 同步执行一条命令并等待结束（用于编译、npm install） */
function runLineSync(commandLine, cwd) {
  const result = IS_WIN
    ? spawnSync('cmd.exe', ['/d', '/s', '/c', commandLine], {
        cwd,
        stdio: 'inherit',
        windowsVerbatimArguments: true,
        env: { ...process.env, FORCE_COLOR: '1' }
      })
    : spawnSync('sh', ['-c', commandLine], {
        cwd,
        stdio: 'inherit',
        env: { ...process.env, FORCE_COLOR: '1' }
      })
  return result.status === 0
}

function killTree(child) {
  if (!child || child.exitCode !== null || child.killed) return
  if (IS_WIN) {
    spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    try {
      process.kill(child.pid, 'SIGTERM')
    } catch {
      /* 进程已退出 */
    }
  }
}

function shutdown(code = 0) {
  if (shuttingDown) return
  shuttingDown = true
  log(`\n${C.yellow}正在停止所有子进程…${C.reset}`)
  for (const child of children) killTree(child)
  setTimeout(() => process.exit(code), 500)
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

// ---------------------------------------------------------------- Maven / 构建

function onPath(exe) {
  const probe = spawnSync(IS_WIN ? 'where' : 'which', [exe], { stdio: 'ignore' })
  return probe.status === 0
}

function firstExisting(paths) {
  for (const p of paths) if (isFile(p)) return p
  return null
}

/**
 * 定位 Maven：本机 mvn 不在 PATH 上，因此依次尝试
 * MAVEN_HOME/M2_HOME → PATH → ~/.m2/wrapper/dists → IntelliJ 内置 Maven。
 */
function resolveMvn() {
  if (mvnCache !== undefined) return mvnCache
  const exe = IS_WIN ? 'mvn.cmd' : 'mvn'
  const candidates = []

  for (const home of [process.env.MAVEN_HOME, process.env.M2_HOME]) {
    if (home) candidates.push(path.join(home, 'bin', exe))
  }

  const dists = path.join(os.homedir(), '.m2', 'wrapper', 'dists')
  for (const dist of safeReaddir(dists)) {
    const distDir = path.join(dists, dist)
    for (const hash of safeReaddir(distDir)) {
      const hashDir = path.join(distDir, hash)
      for (const mavenHome of safeReaddir(hashDir)) {
        candidates.push(path.join(hashDir, mavenHome, 'bin', exe))
      }
    }
  }

  if (IS_WIN) {
    const jetbrains = 'C:\\Program Files\\JetBrains'
    for (const ide of safeReaddir(jetbrains)) {
      candidates.push(path.join(jetbrains, ide, 'plugins', 'maven', 'lib', 'maven3', 'bin', 'mvn.cmd'))
    }
  }

  mvnCache = firstExisting(candidates) || (onPath('mvn') ? exe : null)
  if (!mvnCache) {
    log(`${C.red}未找到 Maven。请安装 Maven 或设置 MAVEN_HOME 环境变量。${C.reset}`)
  }
  return mvnCache
}

/** 递归求目录/文件中最新的修改时间 */
function newestMtime(target) {
  let newest = 0
  const stack = [target]
  while (stack.length) {
    const p = stack.pop()
    let stat
    try {
      stat = fs.statSync(p)
    } catch {
      continue
    }
    if (stat.isDirectory()) {
      for (const name of safeReaddir(p)) stack.push(path.join(p, name))
    } else if (stat.mtimeMs > newest) {
      newest = stat.mtimeMs
    }
  }
  return newest
}

function findJar() {
  const jars = safeReaddir(TARGET_DIR)
    .filter((f) => /^refine-loop-.*\.jar$/.test(f))
    .map((f) => path.join(TARGET_DIR, f))
  if (jars.length === 0) return null
  return jars.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0]
}

function needsBuild() {
  const jar = findJar()
  if (!jar) return true
  const jarTime = fs.statSync(jar).mtimeMs
  const inputs = [path.join(ROOT, 'pom.xml'), path.join(ROOT, 'db', 'schema.sql'), path.join(ROOT, 'src', 'main')]
  const newest = Math.max(...inputs.filter((p) => fs.existsSync(p)).map(newestMtime))
  return newest > jarTime
}

function buildBackend({ runTests }) {
  const mvn = resolveMvn()
  if (!mvn) return false

  const args = runTests ? ['-B', 'package'] : ['-B', '-DskipTests', 'package']
  const commandLine = buildCmd([mvn, ...args])
  log(`\n${C.cyan}▶ 编译后端${C.reset} ${C.dim}(${runTests ? '含测试' : '跳过测试'})${C.reset}`)
  log(`${C.dim}$ ${commandLine}${C.reset}\n`)

  const ok = runLineSync(commandLine, ROOT)
  if (!ok) log(`${C.red}✖ 后端编译失败${C.reset}`)
  return ok
}

function ensureJar(forceBuild) {
  if (forceBuild || needsBuild()) {
    if (!buildBackend({ runTests: false })) return null
  }
  const jar = findJar()
  if (!jar) {
    log(`${C.red}未找到 target/refine-loop-*.jar，请先执行 node start.js build${C.reset}`)
    return null
  }
  return jar
}

// ---------------------------------------------------------------- 前端

function ensureFrontendDeps() {
  if (fs.existsSync(path.join(FRONTEND_DIR, 'node_modules'))) return true
  log(`\n${C.cyan}▶ 安装前端依赖${C.reset} ${C.dim}(frontend/node_modules 不存在)${C.reset}\n`)
  const ok = runLineSync(buildCmd(['npm', 'install']), FRONTEND_DIR)
  if (!ok) log(`${C.red}✖ npm install 失败${C.reset}`)
  return ok
}

// ---------------------------------------------------------------- 启动项

function startBackend(forceBuild) {
  const jar = ensureJar(forceBuild)
  if (!jar) return null

  const javaExe = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', IS_WIN ? 'java.exe' : 'java')
    : 'java'
  const commandLine = buildCmd([javaExe, '-jar', jar, `--spring.profiles.active=${BACKEND_PROFILE}`])

  log(`\n${C.cyan}▶ 启动后端${C.reset} ${C.dim}profile=${BACKEND_PROFILE}  jar=${path.basename(jar)}${C.reset}`)
  log(`${C.dim}$ ${commandLine}${C.reset}\n`)
  return spawnLine('backend', C.blue, commandLine, ROOT)
}

function startFrontend() {
  if (!ensureFrontendDeps()) return null

  const commandLine = buildCmd(['npm', 'run', 'dev'])
  log(`\n${C.cyan}▶ 启动前端${C.reset} ${C.dim}(vite dev server)${C.reset}`)
  log(`${C.dim}$ ${commandLine}${C.reset}\n`)
  return spawnLine('frontend', C.magenta, commandLine, FRONTEND_DIR)
}

function pingHealth() {
  return new Promise((resolve) => {
    const req = http.get(HEALTH_URL, { timeout: 1000 }, (res) => {
      res.resume()
      resolve(res.statusCode === 200)
    })
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.on('error', () => resolve(false))
  })
}

/** 等后端健康检查通过；后端进程提前退出则返回 'dead' */
async function waitForBackend(child) {
  log(`\n${C.dim}等待后端就绪：${HEALTH_URL}（最长 ${HEALTH_TIMEOUT_MS / 1000}s）${C.reset}`)
  const started = Date.now()
  while (Date.now() - started < HEALTH_TIMEOUT_MS) {
    if (shuttingDown) return 'aborted'
    if (child && child.exitCode !== null) return 'dead'
    if (await pingHealth()) {
      log(`\n${C.green}✔ 后端已就绪${C.reset}`)
      return 'ready'
    }
    process.stdout.write(`${C.dim}.${C.reset}`)
    await sleep(1000)
  }
  log(`\n${C.red}✖ 等待后端超时${C.reset}`)
  return 'timeout'
}

function printEndpoints(withFrontend) {
  log(`\n${C.green}━━ refine-loop 已启动 ━━${C.reset}`)
  log(`  后端 API   ${C.blue}http://127.0.0.1:${BACKEND_PORT}/api${C.reset}`)
  log(`  健康检查   ${C.blue}${HEALTH_URL}${C.reset}`)
  log(`  API 文档   ${C.blue}http://127.0.0.1:${BACKEND_PORT}/swagger-ui.html${C.reset}`)
  if (withFrontend) log(`  前端页面   ${C.magenta}http://127.0.0.1:${FRONTEND_PORT}${C.reset}`)
  log(`${C.dim}  按 Ctrl+C 停止${C.reset}\n`)
}

// ---------------------------------------------------------------- 入口

function usage() {
  log(`
${C.cyan}refine-loop 开发启动器${C.reset}

  node start.js              前后端一起启动
  node start.js all          同上（后端就绪后再起前端）
  node start.js backend      只启动后端
  node start.js frontend     只启动前端
  node start.js build        只编译后端（跳过测试）
  node start.js build --test 只编译后端并跑全量测试

  --build   强制重新编译后端
`)
}

async function main() {
  const argv = process.argv.slice(2)
  const flags = new Set(argv.filter((a) => a.startsWith('-')))
  const command = argv.find((a) => !a.startsWith('-')) || 'all'
  const forceBuild = flags.has('--build')

  if (flags.has('-h') || flags.has('--help') || command === 'help') {
    usage()
    return
  }

  switch (command) {
    case 'all': {
      const backend = startBackend(forceBuild)
      if (!backend) process.exit(1)

      backend.on('exit', (code) => {
        if (!shuttingDown) {
          log(`\n${C.red}后端进程退出（code=${code}），一并停止前端${C.reset}`)
          shutdown(code === 0 ? 0 : 1)
        }
      })

      const state = await waitForBackend(backend)
      if (state !== 'ready') {
        shutdown(1)
        return
      }

      const frontend = startFrontend()
      if (!frontend) {
        shutdown(1)
        return
      }
      frontend.on('exit', (code) => {
        if (!shuttingDown) {
          log(`\n${C.yellow}前端进程退出（code=${code}）${C.reset}`)
        }
      })

      printEndpoints(true)
      break
    }

    case 'backend':
    case 'be': {
      const backend = startBackend(forceBuild)
      if (!backend) process.exit(1)
      backend.on('exit', (code) => {
        if (!shuttingDown) shutdown(code === 0 ? 0 : 1)
      })
      printEndpoints(false)
      break
    }

    case 'frontend':
    case 'fe': {
      const frontend = startFrontend()
      if (!frontend) process.exit(1)
      frontend.on('exit', (code) => {
        if (!shuttingDown) shutdown(code === 0 ? 0 : 1)
      })
      log(`\n${C.green}前端开发服务器启动中：${C.magenta}http://127.0.0.1:${FRONTEND_PORT}${C.reset}${C.dim}（/api 代理到 8080）${C.reset}`)
      log(`${C.dim}按 Ctrl+C 停止${C.reset}\n`)
      break
    }

    case 'build': {
      const ok = buildBackend({ runTests: flags.has('--test') })
      process.exit(ok ? 0 : 1)
      break
    }

    default:
      log(`${C.red}未知命令：${command}${C.reset}`)
      usage()
      process.exit(1)
  }
}

main().catch((err) => {
  log(`${C.red}启动脚本异常：${err.stack || err.message}${C.reset}`)
  shutdown(1)
})
