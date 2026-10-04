export function createLatestAsync() {
  let version = 0
  return {
    begin: () => ++version,
    invalidate: () => { ++version },
    isCurrent: (candidate: number) => candidate === version
  }
}
