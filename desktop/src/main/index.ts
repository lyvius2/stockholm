import { join } from 'node:path'
import { BrowserWindow, app, ipcMain, nativeTheme, shell } from 'electron'
import type { ApiRequest } from '../preload/bridge'
import { DaemonApi } from './api'
import { DaemonProcess, probeDaemon } from './daemon'

// 마법사·로그인은 폭 1100 으로 시작하고, 메인이 열리면 넓힘
const WIZARD_SIZE = { width: 1100, height: 760 }
const MAIN_SIZE = { width: 1440, height: 900 }
const WINDOW_MIN_WIDTH = 1100
const WINDOW_MIN_HEIGHT = 720
const DAEMON_START_TIMEOUT_MS = 60_000

const dataDir = process.env['STOCKHOLM_DATA_DIR'] ?? join(app.getPath('appData'), 'Stockholm')
const daemonApi = new DaemonApi(dataDir)
const daemonProcess = new DaemonProcess(join(process.resourcesPath, 'daemon'), dataDir)

function createMainWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: WIZARD_SIZE.width,
    height: WIZARD_SIZE.height,
    minWidth: WINDOW_MIN_WIDTH,
    minHeight: WINDOW_MIN_HEIGHT,
    show: false,
    titleBarStyle: 'hiddenInset',
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#0f1418' : '#f3f5f7',
    webPreferences: {
      // 샌드박스 렌더러의 preload 는 CommonJS 여야 함(ESM preload 는 sandbox 해제 필요)
      preload: join(import.meta.dirname, '../preload/index.cjs'),
      // 렌더러는 Node 에 닿지 못함. preload 가 노출하는 최소 API 만 씀
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  window.once('ready-to-show', () => window.show())
  window.webContents.setWindowOpenHandler(({ url }) => {
    // 외부 링크는 항상 기본 브라우저로 염. 우리 창 안에 남의 페이지를 띄우지 않음
    void shell.openExternal(url)
    return { action: 'deny' }
  })

  const devServerUrl = process.env['ELECTRON_RENDERER_URL']
  if (devServerUrl !== undefined) {
    void window.loadURL(devServerUrl)
  } else {
    void window.loadFile(join(import.meta.dirname, '../renderer/index.html'))
  }
  return window
}

function registerIpc(): void {
  ipcMain.handle('daemon:status', () => probeDaemon())
  ipcMain.handle('theme:current', () => (nativeTheme.shouldUseDarkColors ? 'dark' : 'light'))
  ipcMain.handle('api:request', (_event, request: ApiRequest) => daemonApi.request(request))
  ipcMain.handle('session:has', () => daemonApi.hasSession())
  ipcMain.handle('session:clear', () => daemonApi.clearSession())
  ipcMain.handle('app:version', () => app.getVersion())
  ipcMain.handle('app:expand-for-main', (event) => {
    const window = BrowserWindow.fromWebContents(event.sender)
    if (window === null) return
    const { width, height } = window.getBounds()
    if (width < MAIN_SIZE.width || height < MAIN_SIZE.height) {
      window.setSize(Math.max(width, MAIN_SIZE.width), Math.max(height, MAIN_SIZE.height), true)
      window.center()
    }
  })
}

void app.whenReady().then(async () => {
  registerIpc()
  createMainWindow()
  const outcome = await daemonProcess.startIfNeeded()
  if (outcome === 'spawned') {
    daemonApi.forgetLocalToken()
    void (await import('./daemon')).waitForDaemon(DAEMON_START_TIMEOUT_MS)
  }
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow()
  })
})

app.on('before-quit', () => daemonProcess.stop())

// TODO(1단계 9번 후속): 창을 닫으면 메뉴바 트레이로 상주. 지금은 마지막 창을 닫으면 종료함
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
