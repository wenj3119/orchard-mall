export function displayAddress(address: { provinceName: string; cityName: string; districtName: string; detail: string }) {
  const region = `${address.provinceName || ''}${address.cityName || ''}${address.districtName || ''}`
  const detail = address.detail || ''
  return region + (region && detail.startsWith(region) ? detail.slice(region.length) : detail)
}
