import { contextBridge, ipcRenderer } from 'electron'
import type { DaemonStatus, StockholmBridge, ThemeName } from './bridge'

const bridge: StockholmBridge = {
  daemon: { status: () => ipcRenderer.invoke('daemon:status') as Promise<DaemonStatus> },
  theme: { current: () => ipcRenderer.invoke('theme:current') as Promise<ThemeName> },
}

contextBridge.exposeInMainWorld('stockholm', bridge)
