export type webReadConfig = {
  theme: number
  /** 正文文字颜色，空字符串表示按主题自动选择 */
  textColor: string
  font: number
  fontSize: number
  readWidth: number
  infiniteLoading: boolean
  customFontName: string
  jumpDuration: number
  spacing: {
    paragraph: number
    line: number
    letter: number
  }
}
