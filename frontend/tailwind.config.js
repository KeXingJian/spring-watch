/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts,js,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        mono: ['ui-monospace', 'Consolas', 'SF Mono', 'monospace']
      }
    }
  },
  plugins: [require('daisyui')],
  daisyui: {
    themes: [
      {
        watch: {
          'primary': '#2563eb',
          'primary-content': '#ffffff',
          'secondary': '#64748b',
          'secondary-content': '#ffffff',
          'accent': '#0891b2',
          'accent-content': '#ffffff',
          'neutral': '#1e293b',
          'neutral-content': '#f1f5f9',
          'base-100': '#ffffff',
          'base-200': '#f8fafc',
          'base-300': '#e2e8f0',
          'base-content': '#1e293b',
          'info': '#0ea5e9',
          'info-content': '#ffffff',
          'success': '#16a34a',
          'success-content': '#ffffff',
          'warning': '#ea580c',
          'warning-content': '#ffffff',
          'error': '#dc2626',
          'error-content': '#ffffff',
          '--rounded-box': '0.5rem',
          '--rounded-btn': '0.375rem',
          '--rounded-badge': '0.375rem',
          '--border-btn': '1px',
          '--tab-radius': '0.375rem'
        }
      }
    ],
    base: true,
    styled: true,
    utils: true,
    logs: false
  }
}
