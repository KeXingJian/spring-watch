import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/apps' },
  { path: '/apps', component: () => import('@/views/AppsView.vue') },
  { path: '/app-detail', component: () => import('@/views/AppDetailView.vue') },
  { path: '/logs', component: () => import('@/views/LogsView.vue') },
  { path: '/alert-rules', component: () => import('@/views/AlertRulesView.vue') },
  { path: '/alert-history', component: () => import('@/views/AlertHistoryView.vue') },
  { path: '/email-config', component: () => import('@/views/EmailConfigView.vue') },
  { path: '/self-monitor', component: () => import('@/views/SelfMonitorView.vue') }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
