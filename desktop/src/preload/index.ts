import { contextBridge, ipcRenderer } from 'electron'
import type { ApiRequest, ApiResponse, DaemonStatus, StockholmBridge, ThemeName } from './bridge'

const bridge: StockholmBridge = {
  daemon: { status: () => ipcRenderer.invoke('daemon:status') as Promise<DaemonStatus> },
  theme: { current: () => ipcRenderer.invoke('theme:current') as Promise<ThemeName> },
  api: {
    request: (request: ApiRequest) =>
      ipcRenderer.invoke('api:request', request) as Promise<ApiResponse>,
  },
  session: {
    hasSession: () => ipcRenderer.invoke('session:has') as Promise<boolean>,
    clear: () => ipcRenderer.invoke('session:clear') as Promise<void>,
  },
  app: {
    version: () => ipcRenderer.invoke('app:version') as Promise<string>,
    expandForMain: () => ipcRenderer.invoke('app:expand-for-main') as Promise<void>,
  },
}

contextBridge.exposeInMainWorld('stockholm', bridge)
