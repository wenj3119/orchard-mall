export type CancelStatus = 'PENDING_PAYMENT' | 'CLOSE_PENDING' | 'PAYMENT_EXCEPTION' | 'PAID' | 'CANCELLED' | 'CLOSED'
export type CancelOutcome = { status: CancelStatus | 'UNKNOWN'; requestError?: unknown; dismissed?: boolean }

export function createOrderCancellation() {
  let busy = false
  return {
    async run(confirm: () => Promise<boolean>, cancel: () => Promise<CancelStatus>, read: () => Promise<CancelStatus>): Promise<CancelOutcome | undefined> {
      if (busy) return undefined
      busy = true
      try {
        if (!await confirm()) return { status: 'PENDING_PAYMENT', dismissed: true }
        try {
          const status = await cancel()
          if (status === 'CANCELLED' || status === 'CLOSED' || status === 'CLOSE_PENDING' || status === 'PAYMENT_EXCEPTION' || status === 'PAID') return { status }
          return { status: await read() }
        } catch (requestError) {
          try { return { status: await read(), requestError } }
          catch { return { status: 'UNKNOWN', requestError } }
        }
      } finally { busy = false }
    }
  }
}
