import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: true
})

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(text)
}
