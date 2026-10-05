import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import type {
  ApiRequest,
  ApiResponse,
  DaemonStatus,
  StockholmBridge,
  StreamConnectionState,
  StreamSymbol,
  ThemeName,
  Unsubscribe,
} from './bridge'
import type { StreamServerMessage } from '../renderer/generated/stream-server-message'

// 렌더러가 넘긴 콜백을 IPC 리스너로 감싸고, 떼는 함수를 돌려줌
function listen<T>(channel: string, listener: (payload: T) => void): Unsubscribe {
  const handler = (_event: IpcRendererEvent, payload: T): void => listener(payload)
  ipcRenderer.on(channel, handler)
  return () => ipcRenderer.removeListener(channel, handler)
}

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
    onEnded: (listener) => listen<null>('session:ended', () => listener()),
  },
  stream: {
    subscribe: (symbols: readonly StreamSymbol[]) =>
      ipcRenderer.invoke('stream:subscribe', [...symbols]) as Promise<void>,
    close: () => ipcRenderer.invoke('stream:close') as Promise<void>,
    state: () => ipcRenderer.invoke('stream:state') as Promise<StreamConnectionState>,
    onMessages: (listener) => listen<StreamServerMessage[]>('stream:messages', listener),
    onState: (listener) => listen<StreamConnectionState>('stream:state', listener),
  },
  app: {
    version: () => ipcRenderer.invoke('app:version') as Promise<string>,
    expandForMain: () => ipcRenderer.invoke('app:expand-for-main') as Promise<void>,
  },
}

contextBridge.exposeInMainWorld('stockholm', bridge)
