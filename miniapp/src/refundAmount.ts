/** Parse a yuan input without floating point conversion or rounding. */
export function yuanToFen(input: string): number | null {
  if (!/^(0|[1-9]\d*)(?:\.(\d{1,2}))?$/.test(input)) return null
  const [yuan, cents = ''] = input.split('.')
  const fen = Number(yuan) * 100 + Number(cents.padEnd(2, '0'))
  return Number.isSafeInteger(fen) && fen > 0 ? fen : null
}

export function fenToYuan(fen: number): string {
  return `${Math.floor(fen / 100)}.${String(fen % 100).padStart(2, '0')}`
}
