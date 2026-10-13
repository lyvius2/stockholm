import { useDrawersStore } from '@renderer/data/store/drawers'

describe('useDrawersStore', () => {
  beforeEach(() => useDrawersStore.getState().reset())

  it('같은 쪽은 겹쳐 열리고, 반대쪽을 열면 열려 있던 쪽이 닫힘(좌우 배타)', () => {
    const store = useDrawersStore.getState()
    store.openDrawer('watchlist')
    store.openDrawer('community')
    expect(useDrawersStore.getState().open).toEqual(['watchlist', 'community'])

    store.openDrawer('movers')
    expect(useDrawersStore.getState().open).toEqual(['movers'])
    store.openDrawer('stockInfo')
    expect(useDrawersStore.getState().open).toEqual(['movers', 'stockInfo'])

    store.openDrawer('watchlist')
    expect(useDrawersStore.getState().open).toEqual(['watchlist'])
  })

  it('토글은 열려 있으면 닫고, Esc 는 나중에 연 것부터 닫음', () => {
    const store = useDrawersStore.getState()
    store.toggle('watchlist')
    store.toggle('community')
    store.toggle('watchlist')
    expect(useDrawersStore.getState().open).toEqual(['community'])

    store.openDrawer('watchlist')
    expect(store.closeTopmost()).toBe(true)
    expect(useDrawersStore.getState().open).toEqual(['community'])
    expect(store.closeTopmost()).toBe(true)
    expect(store.closeTopmost()).toBe(false)
  })

  it('폭은 15~50% 로 잘리고 더블클릭 복원은 기본값', () => {
    const store = useDrawersStore.getState()
    store.setWidth('movers', 70)
    expect(useDrawersStore.getState().widths.movers).toBe(50)
    store.setWidth('movers', 3)
    expect(useDrawersStore.getState().widths.movers).toBe(15)
    store.resetWidth('movers')
    expect(useDrawersStore.getState().widths.movers).toBe(25)
  })
})
