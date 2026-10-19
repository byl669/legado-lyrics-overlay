// 本地预览用 mock 服务：静态托管 dist + 伪造阅读后端接口，仅供 UI 观感验证
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const dist = path.join(root, 'dist')
const PORT = Number(process.env.PORT || 8123)

const bookUrl = 'mock://book/1'
const books = [
  {
    name: '长夜将明',
    author: '示例作者',
    bookUrl,
    kind: '玄幻',
    tocUrl: bookUrl + '/toc',
    origin: 'mock',
    originName: '本地Mock',
    coverUrl: '',
    intro: '这是一段用于验证书籍卡片可读性的简介文字。',
    type: 0,
    group: 0,
    latestChapterTitle: '第三章 风起',
    latestChapterTime: Date.now(),
    lastCheckTime: Date.now(),
    lastCheckCount: 1,
    totalChapterNum: 3,
    durChapterTitle: '第二章 归途',
    durChapterIndex: 1,
    durChapterPos: 0,
    durChapterTime: Date.now(),
    canUpdate: true,
    order: 0,
  },
  {
    name: '山海旧事',
    author: '另一个作者',
    bookUrl: bookUrl + '2',
    kind: '都市',
    tocUrl: bookUrl + '2/toc',
    origin: 'mock',
    originName: '本地Mock',
    coverUrl: '',
    intro: '第二本书的简介文字，用于验证暗色下的次级文字颜色。',
    type: 0,
    group: 0,
    latestChapterTitle: '第十章 收束',
    latestChapterTime: Date.now() - 86400000,
    lastCheckTime: Date.now() - 86400000,
    lastCheckCount: 0,
    totalChapterNum: 10,
    durChapterTitle: '第九章 转折',
    durChapterIndex: 8,
    durChapterPos: 0,
    durChapterTime: Date.now() - 86400000,
    canUpdate: false,
    order: 1,
  },
]

const catalog = ['第一章 启程', '第二章 归途', '第三章 风起'].map((title, index) => ({
  url: bookUrl + '/chapter/' + index,
  title,
  isVolume: false,
  baseUrl: bookUrl,
  bookUrl,
  index,
  isVip: false,
  isPay: false,
}))

const chapterText = [
  '　　夜色像墨一样压下来，远处的灯火在雨雾里晕成一团模糊的黄。',
  '他坐在窗边，把书翻到上次停下的那一页，纸页上还留着上回折角留下的浅痕。',
  '　　风从窗缝里钻进来，吹得灯焰歪了一下，又慢慢正了回去。屋子里安静得能听见自己的呼吸声。',
  '　　这一章的文字用于验证正文在各种背景色下的可读性——笔画要清楚，字距要舒展，段落之间要留出呼吸的空间。',
  '　　远处传来更鼓，一下，两下，三下。他合上书，抬头看了一眼墙上那只旧钟。',
  '　　钟摆来回摆动，把时间切成均匀的片段。夜里读书的人，总觉得自己和时间贴得很近。',
  '　　灯火渐暗。他把灯芯挑亮了一些，屋子重新亮起来，纸上的字也清晰了。',
].join('\n')

const json = (res, obj) => {
  const body = JSON.stringify(obj)
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}
const ok = (res, data) => json(res, { isSuccess: true, errorMsg: '', data })

const readConfig = () => {
  // 允许通过 ?theme=&text= 覆盖，便于对比不同配色
  return { theme: 7, textColor: '', font: 0, fontSize: 18, readWidth: 800, infiniteLoading: false, customFontName: '', jumpDuration: 0, spacing: { paragraph: 1, line: 0.8, letter: 0 } }
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const pathname = decodeURIComponent(url.pathname)

  if (pathname === '/getReadConfig') {
    const config = readConfig()
    const cookies = Object.fromEntries(
      (req.headers.cookie || '')
        .split(';')
        .map(item => item.trim().split('='))
        .filter(pair => pair.length === 2),
    )
    if (cookies.mockTheme) config.theme = Number(cookies.mockTheme)
    if (cookies.mockText) config.textColor = decodeURIComponent(cookies.mockText)
    return ok(res, JSON.stringify(config))
  }
  if (pathname === '/getBookshelf') return ok(res, books)
  if (pathname === '/getChapterList') return ok(res, catalog)
  if (pathname === '/getBookContent') return ok(res, chapterText)
  if (pathname === '/getReviewSummary') return ok(res, { counts: {}, keys: {} })
  if (pathname === '/saveBookProgress' || pathname === '/saveReadConfig') return ok(res, '')
  if (pathname === '/getBookSourcesForManagement' || pathname === '/getBookSourceCheckStates') {
    return ok(res, { sources: [], states: [], sessionToken: null })
  }

  let file = path.join(dist, pathname === '/' ? 'index.html' : pathname)
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(dist, 'index.html')
  }
  let content = fs.readFileSync(file)
  if (file.endsWith('index.html')) {
    const inject = `
<script>
(function(){
  var p = new URLSearchParams(location.search);
  if (p.get('mock') === 'chapter') {
    sessionStorage.setItem('bookUrl', '${bookUrl}');
    sessionStorage.setItem('bookName', '长夜将明');
    sessionStorage.setItem('bookAuthor', '示例作者');
    sessionStorage.setItem('chapterIndex', '1');
    sessionStorage.setItem('chapterPos', '0');
    sessionStorage.setItem('isSeachBook', 'false');
  }
  document.cookie = 'mockTheme=;max-age=0;path=/';
  document.cookie = 'mockText=;max-age=0;path=/';
  if (p.get('theme')) document.cookie = 'mockTheme=' + p.get('theme') + ';path=/';
  if (p.has('text')) document.cookie = 'mockText=' + p.get('text') + ';path=/';
  if (p.get('probe') === '1') {
    var sel = JSON.parse(p.get('sel') || '[]');
    var clickSel = p.get('click');
    var clickIdx = Number(p.get('clickIdx') || 0);
    setTimeout(function(){
      if (clickSel) {
        var targets = document.querySelectorAll(clickSel);
        var t = targets[clickIdx];
        if (t) {
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (type) {
            t.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
          });
        }
      }
      setTimeout(function(){
      var out = {};
      sel.forEach(function(s){
        var el = document.querySelector(s.q);
        if (!el) { out[s.k] = 'MISSING ' + s.q; return; }
        var cs = getComputedStyle(el);
        out[s.k] = { bg: cs.backgroundColor, color: cs.color, border: cs.borderColor, size: cs.fontSize };
      });
      var root = getComputedStyle(document.documentElement);
      out.__htmlClass = document.documentElement.className;
      out.__elBg = root.getPropertyValue('--el-bg-color').trim();
      out.__elTextPrimary = root.getPropertyValue('--el-text-color-primary').trim();
      out.__themeCount = document.querySelectorAll('.theme-item').length;
      out.__textColorCount = document.querySelectorAll('.text-color-item').length;
      out.__textColorLabels = Array.prototype.map.call(
        document.querySelectorAll('.text-color-item'),
        function(el){ return el.textContent.trim(); },
      );
      document.documentElement.innerHTML = '<pre id="probe">' + JSON.stringify(out, null, 1) + '</pre>';
      }, 1200);
    }, 2000);
  }
})();
</script>
`
    content = Buffer.from(content.toString().replace('</head>', inject + '</head>'))
  }
  res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' })
  res.end(content)
})

server.listen(PORT, () => console.log('preview on http://127.0.0.1:' + PORT))
