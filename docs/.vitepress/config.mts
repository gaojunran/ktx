import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * Build the `/cli/` sidebar from `docs/cli/commands.json`, the JSON spec
 * `mise run render` writes alongside the markdown.
 *
 * Schema (relevant slice):
 *   { name: "ktx", cmd: { subcommands: { run: { subcommands: { ... } }, ... } } }
 *
 * Returns nested sidebar items: each top-level command, with its own
 * subcommands as `items`. Falls back to an empty array when the file
 * doesn't exist yet (first clone before any render run); docs:build then
 * still succeeds and shows an empty CLI section.
 */
function buildCliSidebar() {
  const jsonPath = path.resolve(__dirname, '../cli/commands.json')
  if (!fs.existsSync(jsonPath)) return []
  const spec = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))
  const rootName: string = spec.name ?? 'ktx'
  const subs: Record<string, any> = spec.cmd?.subcommands ?? {}
  const topLevel = Object.entries(subs).map(([name, cmd]: [string, any]) => {
    const childSubs: Record<string, any> = cmd.subcommands ?? {}
    const children = Object.keys(childSubs).map(child => ({
      text: `${rootName} ${name} ${child}`,
      link: `/cli/${name}/${child}`,
    }))
    if (children.length > 0) {
      return {
        text: `${rootName} ${name}`,
        link: `/cli/${name}`,
        collapsed: true,
        items: children,
      }
    }
    return { text: `${rootName} ${name}`, link: `/cli/${name}` }
  })
  return [
    {
      text: 'CLI Reference',
      items: [
        { text: 'Overview', link: '/cli/' },
        ...topLevel,
      ],
    },
  ]
}

export default withMermaid(defineConfig({
  title: 'ktx',
  description: 'A modern Kotlin script runner. uv / bun-class experience for .kts.',
  cleanUrls: true,
  lastUpdated: true,

  // Deployed at https://gaojunran.github.io/ktx/ — base must match the repo
  // name so all assets resolve correctly. Override with KTX_DOCS_BASE to
  // serve from a different prefix (e.g. a custom domain at root).
  base: process.env.KTX_DOCS_BASE ?? '/ktx/',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/ktx/favicon.png' }],
    ['meta', { name: 'theme-color', content: '#7F52FF' }],
  ],

  themeConfig: {
    // Nav-bar logo. The glow filter is applied via custom CSS in the theme.
    logo: { src: '/logo-mark.png', width: 20, height: 20 },

    // Site title remains "ktx" plain text next to the logo.
    siteTitle: 'ktx',

    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'CLI', link: '/cli/' },
      { text: 'Performance', link: '/performance' },
      { text: 'Design', link: '/design/architecture' },
      { text: 'FAQ', link: '/faq' },
      { text: 'Releases', link: 'https://github.com/gaojunran/ktx/releases' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Guide',
          items: [
            { text: 'Getting Started', link: '/guide/getting-started' },
            { text: 'Shell Scripting', link: '/guide/shell' },
            { text: 'Daemon Mode', link: '/guide/daemon' },
            { text: 'Lockfiles', link: '/guide/lockfile' },
            { text: 'Toolchain Management', link: '/guide/toolchain' },
            { text: 'Compiling Scripts', link: '/guide/compile' },
          ],
        },
      ],
      '/cli/': buildCliSidebar(),
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
