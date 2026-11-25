<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../api/auth'
import { asApiError, describeError } from '../api/http'
import type { WatchlistItem } from '../api/types'
import StateBlock from '../components/StateBlock.vue'
import StatusChip from '../components/StatusChip.vue'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'
import { useUiStore } from '../stores/ui'
import { formatTimestamp } from '../utils/format'
import { PREFERENCE_FIELDS, fromPreferenceDraft, toPreferenceDraft } from '../utils/preferences'

/** 我的账户：资料 + 改密 + 交易偏好 + 自选股（全部按当前登录用户隔离）。 */
const auth = useAuthStore()
const profile = useProfileStore()
const ui = useUiStore()
const router = useRouter()

/* ---------------- 交易偏好 ---------------- */
const draft = ref<Record<string, string>>(toPreferenceDraft({}))

watch(
  () => profile.preferences,
  (values) => {
    draft.value = toPreferenceDraft(values)
  },
  { immediate: true }
)

async function savePreferences() {
  await profile.savePreferences(fromPreferenceDraft(draft.value, profile.preferences))
}

/* ---------------- 改密 ---------------- */
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const passwordError = ref('')
const passwordSaving = ref(false)

async function submitPassword() {
  passwordError.value = ''
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    passwordError.value = '请填写原密码与新密码'
    return
  }
  if (passwordForm.newPassword.length < 8) {
    passwordError.value = '新密码长度至少 8 位'
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  passwordSaving.value = true
  try {
    await changePassword(passwordForm.oldPassword, passwordForm.newPassword)
    ui.pushToast('ok', '密码已修改，请使用新密码重新登录')
    await auth.logout()
    await router.replace('/login')
  } catch (error) {
    passwordError.value = describeError(asApiError(error))
  } finally {
    passwordSaving.value = false
  }
}

/* ---------------- 自选股 ---------------- */
const watchForm = reactive({ tsCode: '', stockName: '', groupName: '', note: '' })
const watchError = ref('')

async function addWatchlist() {
  watchError.value = ''
  if (!watchForm.tsCode.trim()) {
    watchError.value = '请输入标的代码（如 000001.SZ）'
    return
  }
  const ok = await profile.addWatchlistItem({
    tsCode: watchForm.tsCode.trim(),
    stockName: watchForm.stockName.trim() || null,
    groupName: watchForm.groupName.trim() || null,
    note: watchForm.note.trim() || null
  })
  if (ok) {
    watchForm.tsCode = ''
    watchForm.stockName = ''
    watchForm.groupName = ''
    watchForm.note = ''
  }
}

async function removeWatchlist(item: WatchlistItem) {
  const ok = await ui.confirm({
    title: '移出自选股',
    body: `确认将 ${item.tsCode}${item.stockName ? `（${item.stockName}）` : ''} 移出自选股？`,
    okText: '移出',
    tone: 'danger'
  })
  if (ok) await profile.removeWatchlistItem(item.id)
}

onMounted(() => {
  void profile.fetchAll()
})
</script>

<template>
  <section>
    <div class="page-h rv">
      <div>
        <h2>我的账户</h2>
        <div class="desc">账号资料、交易偏好与自选股 · 仅本人可见</div>
      </div>
      <div class="right">
        <StatusChip :tone="auth.isAdmin ? 'err' : 'run'" :label="auth.user?.roleLabel || '—'" />
      </div>
    </div>

    <StateBlock
      v-if="profile.error && !profile.loaded"
      kind="error"
      title="账户数据加载失败"
      :description="describeError(profile.error)"
      action-label="重新加载"
      @action="profile.fetchAll()"
    />

    <div v-else class="profile-grid">
      <div class="card rv d1">
        <div class="card-h"><h3>账号资料</h3></div>
        <div class="card-b">
          <div class="kv">
            <div><span class="k">用户名</span><b class="mono">{{ auth.user?.username || '—' }}</b></div>
            <div><span class="k">显示名</span><b>{{ auth.user?.displayName || '—' }}</b></div>
            <div><span class="k">角色</span><b>{{ auth.user?.roleLabel || '—' }}</b></div>
            <div><span class="k">权限码</span><b class="mono" style="font-size: 11.5px">{{ auth.user?.permissions?.join(' · ') || '—' }}</b></div>
          </div>
        </div>
      </div>

      <div class="card rv d2">
        <div class="card-h"><h3>修改密码</h3><span class="sub">修改后需要重新登录</span></div>
        <div class="card-b">
          <div class="form-col">
            <label class="field">
              <span>原密码</span>
              <input v-model="passwordForm.oldPassword" class="inp" type="password" autocomplete="current-password" />
            </label>
            <label class="field">
              <span>新密码</span>
              <input v-model="passwordForm.newPassword" class="inp" type="password" autocomplete="new-password" />
            </label>
            <label class="field">
              <span>确认新密码</span>
              <input v-model="passwordForm.confirmPassword" class="inp" type="password" autocomplete="new-password" />
            </label>
            <div v-if="passwordError" class="alert err">{{ passwordError }}</div>
            <button class="btn pri" :disabled="passwordSaving" @click="submitPassword">
              {{ passwordSaving ? '提交中…' : '修改密码' }}
            </button>
          </div>
        </div>
      </div>

      <div class="card rv d3">
        <div class="card-h"><h3>交易偏好</h3><span class="sub">整组保存 · 未填写的项会被清空</span></div>
        <div class="card-b">
          <div class="form-grid">
            <label v-for="field in PREFERENCE_FIELDS" :key="field.key" class="field">
              <span>{{ field.label }}</span>
              <select v-if="field.kind === 'select'" v-model="draft[field.key]" class="sel">
                <option value="">请选择</option>
                <option v-for="option in field.options" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
              <input
                v-else
                v-model="draft[field.key]"
                class="inp"
                :inputmode="field.kind === 'number' ? 'decimal' : 'text'"
                :placeholder="field.placeholder"
              />
            </label>
          </div>
          <div class="ops" style="margin-top: 14px; justify-content: flex-end">
            <button class="btn pri" :disabled="profile.saving" @click="savePreferences">
              {{ profile.saving ? '保存中…' : '保存偏好' }}
            </button>
          </div>
        </div>
      </div>

      <div class="card rv d4">
        <div class="card-h"><h3>自选股</h3><span class="sub">共 {{ profile.watchlist.length }} 只</span></div>
        <div class="card-b">
          <div class="watch-form">
            <input v-model="watchForm.tsCode" class="inp" placeholder="标的代码 000001.SZ" />
            <input v-model="watchForm.stockName" class="inp" placeholder="名称（可空，自动补全）" />
            <input v-model="watchForm.groupName" class="inp" placeholder="分组（可空）" />
            <input v-model="watchForm.note" class="inp" placeholder="备注（可空）" />
            <button class="btn pri" :disabled="profile.saving" @click="addWatchlist">加入</button>
          </div>
          <div v-if="watchError" class="alert err mt14">{{ watchError }}</div>

          <table class="tbl mt14">
            <thead>
              <tr>
                <th>标的</th>
                <th>名称</th>
                <th>分组</th>
                <th>备注</th>
                <th>加入时间</th>
                <th style="text-align: right">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in profile.watchlist" :key="item.id">
                <td><b class="mono" style="font-size: 12.5px">{{ item.tsCode }}</b></td>
                <td>{{ item.stockName || '—' }}</td>
                <td>{{ item.groupName || '—' }}</td>
                <td class="dim">{{ item.note || '—' }}</td>
                <td class="mono dim" style="font-size: 12px">{{ formatTimestamp(item.createdAt) }}</td>
                <td>
                  <div class="ops" style="justify-content: flex-end">
                    <button class="btn sm danger" :disabled="profile.saving" @click="removeWatchlist(item)">移出</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!profile.watchlist.length">
                <td colspan="6"><div class="empty"><div class="big">⌕</div>还没有自选股</div></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}

.profile-grid > .card:last-child {
  grid-column: 1 / -1;
}

.form-col,
.form-grid {
  display: grid;
  gap: 12px;
}

.form-grid {
  grid-template-columns: 1fr 1fr;
}

.form-grid .field,
.form-col .field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--tx-2);
}

.watch-form {
  display: grid;
  grid-template-columns: 1.2fr 1.4fr 1fr 1.4fr auto;
  gap: 10px;
  align-items: center;
}

@media (max-width: 1280px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
  .watch-form {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
