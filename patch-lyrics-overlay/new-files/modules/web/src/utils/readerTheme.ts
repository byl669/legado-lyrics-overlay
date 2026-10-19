import type { webReadConfig } from '@/web'
import {
  NIGHT_THEMES,
  OLED_THEME,
  defaultDayTextColor,
  defaultNightTextColor,
} from '@/config/themeConfig'

export const isNightTheme = (theme: number | undefined) =>
  NIGHT_THEMES.includes(theme ?? -1)

export const isOledTheme = (theme: number | undefined) => theme === OLED_THEME

/** 正文颜色：用户指定优先，否则按明暗主题给默认值 */
export const resolveTextColor = (
  config: Pick<webReadConfig, 'theme' | 'textColor'>,
) => {
  const custom = (config.textColor || '').trim()
  if (custom) return custom
  return isNightTheme(config.theme) ? defaultNightTextColor : defaultDayTextColor
}

/**
 * 同步 html 根节点上的主题类名：
 * - dark：element-plus 组件暗色变量
 * - oled：纯黑背景时把面板/弹层压到 #000，并提亮正文与次级文字
 */
export const syncDocumentTheme = (theme: number | undefined) => {
  const root = document.documentElement
  root.classList.toggle('dark', isNightTheme(theme))
  root.classList.toggle('oled', isOledTheme(theme))
}
