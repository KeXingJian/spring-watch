export const PALETTE = [
  '#2563eb',
  '#ea580c',
  '#16a34a',
  '#dc2626',
  '#64748b',
  '#8b5cf6',
  '#0891b2',
  '#db2777'
]

export function pickColor(i: number): string {
  return PALETTE[i % PALETTE.length]
}
