<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { describeError } from '../api/http'
import type { UserSummary } from '../api/types'
import BaseModal from '../components/BaseModal.vue'
import StateBlock from '../components/StateBlock.vue'
import StatusChip from '../components/StatusChip.vue'
import { useUiStore } from '../stores/ui'
import { useUsersStore } from '../stores/users'
import { formatTimestamp } from '../utils/format'

/** 用户管理（仅 ADMIN）：列表 / 新建 / 编辑 / 重置密码 / 删除。 */
const users = useUsersStore()
const ui = useUiStore()

const roleOptions = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'USER', label: '普通用户' }
]

const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editorError = ref('')
const form = reactive({
  username: '',
  password: '',
  displayName: '',
  role: 'USER',
  remark: ''
})

const resetOpen = ref(false)
const resetTarget = ref<UserSummary | null>(null)
const resetPassword = ref('')
const resetError = ref('')

function roleLabel(role: string): string {
  return roleOptions.find((item) => item.value === role)?.label ?? role
}

function roleTone(role: string): 'err' | 'run' {
  return role === 'ADMIN' ? 'err' : 'run'
}

function openCreate() {
  editorMode.value = 'create'
  editingId.value = null
  editorError.value = ''
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.role = 'USER'
  form.remark = ''
  editorOpen.value = true
}

function openEdit(user: UserSummary) {
  editorMode.value = 'edit'
  editingId.value = user.id
  editorError.value = ''
  form.username = user.username
  form.password = ''
  form.displayName = user.displayName ?? ''
  form.role = user.role
  form.remark = ''
  editorOpen.value = true
}

async function submitEditor() {
  editorError.value = ''
  if (editorMode.value === 'create') {
    if (!form.username.trim() || !form.password) {
      editorError.value = '用户名与密码为必填项'
      return
    }
    const ok = await users.create({
      username: form.username.trim(),
      password: form.password,
      displayName: form.displayName.trim() || null,
      role: form.role,
      remark: form.remark.trim() || null
    })
    if (ok) editorOpen.value = false
    return
  }
  if (editingId.value === null) return
  const ok = await users.update(editingId.value, {
    displayName: form.displayName.trim() || null,
    role: form.role,
    remark: form.remark.trim() || null
  })
  if (ok) editorOpen.value = false
}

function openReset(user: UserSummary) {
  resetTarget.value = user
  resetPassword.value = ''
  resetError.value = ''
  resetOpen.value = true
}

async function submitReset() {
  resetError.value = ''
  if (resetTarget.value === null) return
  if (resetPassword.value.length < 8) {
    resetError.value = '密码长度至少 8 位'
    return
  }
  const ok = await users.resetPassword(resetTarget.value.id, resetPassword.value)
  if (ok) resetOpen.value = false
}

async function toggleActive(user: UserSummary) {
  await users.update(user.id, { active: !user.active })
}

async function remove(user: UserSummary) {
  const ok = await ui.confirm({
    title: '删除用户',
    body: `确认删除用户「${user.username}」？其自选股与交易偏好会一并清理，且不可恢复。`,
    okText: '删除',
    tone: 'danger'
  })
  if (ok) await users.remove(user.id)
}

function goPage(next: number) {
  users.page = next
  void users.fetchPage()
}

onMounted(() => {
  void users.fetchPage()
})
</script>

<template>
  <section>
    <div class="page-h rv">
      <div>
        <h2>用户管理</h2>
        <div class="desc">多用户账号与角色权限 · 普通用户只能维护自己的自选股与交易偏好</div>
      </div>
      <div class="right">
        <button class="btn" :disabled="users.loading" @click="users.fetchPage()">刷新</button>
        <button class="btn pri" @click="openCreate">+ 新建用户</button>
      </div>
    </div>

    <div class="card rv d1">
      <div class="card-h">
        <h3>账号列表</h3>
        <span class="sub">共 {{ users.total }} 个账号</span>
        <div class="right">
          <div class="search">
            <input v-model="users.keyword" placeholder="搜索用户名 / 显示名" @keyup.enter="goPage(1)" />
          </div>
          <select v-model="users.role" class="sel" @change="goPage(1)">
            <option value="">全部角色</option>
            <option v-for="option in roleOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </div>
      </div>

      <StateBlock
        v-if="users.error && !users.loaded"
        kind="error"
        title="用户列表加载失败"
        :description="describeError(users.error)"
        action-label="重新加载"
        @action="users.fetchPage()"
      />

      <template v-else>
        <StateBlock v-if="users.loading && !users.loaded" kind="loading" title="正在加载账号列表…" />
        <table v-else class="tbl">
          <thead>
            <tr>
              <th>用户名</th>
              <th>显示名</th>
              <th>角色</th>
              <th style="text-align: center">状态</th>
              <th>最近登录</th>
              <th>创建时间</th>
              <th style="text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users.items" :key="user.id">
              <td><b class="mono" style="font-size: 12.5px">{{ user.username }}</b></td>
              <td>{{ user.displayName || '—' }}</td>
              <td>
                <StatusChip :tone="roleTone(user.role)" :label="roleLabel(user.role)" />
              </td>
              <td style="text-align: center">
                <StatusChip :tone="user.active ? 'ok' : 'idle'" :label="user.active ? '启用' : '停用'" />
              </td>
              <td class="mono dim" style="font-size: 12px">{{ formatTimestamp(user.lastLoginAt) }}</td>
              <td class="mono dim" style="font-size: 12px">{{ formatTimestamp(user.createdAt, { withYear: true }) }}</td>
              <td>
                <div class="ops" style="justify-content: flex-end">
                  <button class="btn sm" @click="openEdit(user)">编辑</button>
                  <button class="btn sm" @click="openReset(user)">重置密码</button>
                  <button class="btn sm" :disabled="users.saving" @click="toggleActive(user)">
                    {{ user.active ? '停用' : '启用' }}
                  </button>
                  <button class="btn sm danger" :disabled="users.saving" @click="remove(user)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!users.items.length">
              <td colspan="7">
                <div class="empty"><div class="big">⌕</div>没有匹配的账号</div>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="pgn">
          <span>第 <b class="mono">{{ users.page }}</b> 页 · 共 {{ users.total }} 条</span>
          <div class="ops">
            <button class="btn sm" :disabled="users.page <= 1 || users.loading" @click="goPage(users.page - 1)">
              上一页
            </button>
            <button
              class="btn sm"
              :disabled="users.page * users.size >= users.total || users.loading"
              @click="goPage(users.page + 1)"
            >
              下一页
            </button>
          </div>
        </div>
      </template>
    </div>

    <BaseModal
      v-model="editorOpen"
      :title="editorMode === 'create' ? '新建用户' : '编辑用户'"
      :subtitle="editorMode === 'edit' ? form.username : '用户名创建后不可修改'"
    >
      <div class="form-grid">
        <label class="field">
          <span>用户名</span>
          <input v-model="form.username" class="inp" :disabled="editorMode === 'edit'" placeholder="登录名" />
        </label>
        <label v-if="editorMode === 'create'" class="field">
          <span>初始密码</span>
          <input v-model="form.password" class="inp" type="password" placeholder="至少 8 位" />
        </label>
        <label class="field">
          <span>显示名</span>
          <input v-model="form.displayName" class="inp" placeholder="可空，默认同用户名" />
        </label>
        <label class="field">
          <span>角色</span>
          <select v-model="form.role" class="sel">
            <option v-for="option in roleOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="field" style="grid-column: 1 / -1">
          <span>备注</span>
          <input v-model="form.remark" class="inp" placeholder="可空" />
        </label>
      </div>
      <div v-if="editorError" class="alert err mt14">{{ editorError }}</div>
      <template #footer>
        <button class="btn" @click="editorOpen = false">取消</button>
        <button class="btn pri" :disabled="users.saving" @click="submitEditor">
          {{ users.saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </BaseModal>

    <BaseModal v-model="resetOpen" title="重置密码" :subtitle="resetTarget?.username">
      <label class="field">
        <span>新密码</span>
        <input v-model="resetPassword" class="inp" type="password" placeholder="至少 8 位" />
      </label>
      <div v-if="resetError" class="alert err mt14">{{ resetError }}</div>
      <template #footer>
        <button class="btn" @click="resetOpen = false">取消</button>
        <button class="btn pri" :disabled="users.saving" @click="submitReset">确认重置</button>
      </template>
    </BaseModal>
  </section>
</template>

<style scoped>
.search input {
  width: 200px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-grid .field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--tx-2);
}
</style>
