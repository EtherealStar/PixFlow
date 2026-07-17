import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export function useAuthRedirect(): {
  goProtected: (target: string) => Promise<void>
} {
  const router = useRouter()
  const auth = useAuthStore()

  async function goProtected(target: string): Promise<void> {
    if (auth.isAuthenticated) {
      await router.push(target)
      return
    }
    await router.push({ name: 'login', query: { redirect: target } })
  }

  return { goProtected }
}
