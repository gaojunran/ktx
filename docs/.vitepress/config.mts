import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
  title: 'ktx',
  description: 'A modern Kotlin script runner. uv / bun-class experience for .kts.',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['link', { rel: 'icon', href: '/favicon.ico' }],
  ],

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Performance', link: '/performance' },
      { text: 'Design', link: '/design/architecture' },
      { text: 'FAQ', link: '/faq' },
      { text: 'GitHub', link: 'https://github.com/gaojunran/ktx' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Guide',
          items: [
            { text: 'Getting Started', link: '/guide/getting-started' },
            { text: 'CLI Reference', link: '/guide/cli' },
            { text: 'Daemon Mode', link: '/guide/daemon' },
            { text: 'Lockfiles', link: '/guide/lockfile' },
            { text: 'Toolchain Management', link: '/guide/toolchain' },
            { text: 'Compiling Scripts', link: '/guide/compile' },
          ],
        },
      ],
      '/design/': [
        {
          text: 'Design',
          items: [
            { text: 'Architecture', link: '/design/architecture' },
            { text: 'Daemon Protocol', link: '/design/daemon-protocol' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/gaojunran/ktx' },
    ],

    footer: {
      message: 'Released under TBD license.',
      copyright: 'Copyright © 2026 ktx contributors',
    },

    search: {
      provider: 'local',
    },

    editLink: {
      pattern: 'https://github.com/gaojunran/ktx/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },
  },

  // Mermaid block configuration; enables graph / sequenceDiagram / pie / etc.
  // inside fenced ```mermaid blocks across all markdown pages.
  mermaid: {
    theme: 'default',
  },
}))
