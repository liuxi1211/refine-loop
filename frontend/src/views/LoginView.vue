<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { describeError } from '../api/http'
import { useAuthStore } from '../stores/auth'

/** 登录页：唯一无需令牌的页面；成功后回到来源页（默认数据总览）。 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const errorText = ref('')

async function submit() {
  errorText.value = ''
  if (!username.value.trim() || !password.value) {
    errorText.value = '请输入用户名和密码'
    return
  }
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/data/overview'
    await router.replace(redirect)
  } catch (error) {
    errorText.value = describeError(auth.error) || describeError(error as Error) || '登录失败'
  }
}
</script>

<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-logo">RL</div>
        <div>
          <div class="brand-name">RefineLoop</div>
          <div class="brand-sub">股票分析与验证闭环 · 投研工作台</div>
        </div>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <label class="field">
          <span>用户名</span>
          <input v-model="username" class="inp" autocomplete="username" placeholder="请输入用户名" />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="password"
            class="inp"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </label>

        <div v-if="errorText" class="alert err">{{ errorText }}</div>

        <button class="btn pri login-btn" type="submit" :disabled="auth.loading">
          <span v-if="auth.loading" class="spinner on-dark" style="margin-right: 4px"></span>
          {{ auth.loading ? '登录中…' : '登 录' }}
        </button>
      </form>

      <div class="login-foot">
        首次使用请联系管理员获取初始化账号；生产环境应通过环境变量设置初始密码，登录后立即修改。
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-shell {
  height: 100vh;
  display: grid;
  place-items: center;
  background: radial-gradient(circle at 20% 20%, #eef2fb 0, var(--bg) 45%);
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--r);
  box-shadow: var(--sh-lg);
  padding: 26px 26px 20px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line-2);
  margin-bottom: 18px;
}

.login-brand .brand-name {
  color: var(--tx);
}

.login-brand .brand-sub {
  color: var(--tx-3);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-form .field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--tx-2);
}

.login-btn {
  width: 100%;
  justify-content: center;
  padding: 9px 14px;
  margin-top: 4px;
}

.login-foot {
  margin-top: 16px;
  font-size: 11.5px;
  color: var(--tx-3);
  line-height: 1.6;
}
</style>
