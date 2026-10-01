export default defineAppConfig({
  pages: ['pages/index/index', 'pages/categories/index', 'pages/cart/index', 'pages/orders/index',
    'pages/product/index', 'pages/login/index', 'pages/addresses/index', 'pages/checkout/index', 'pages/order-detail/index', 'pages/after-sales/index'],
  window: { backgroundTextStyle: 'light', navigationBarBackgroundColor: '#c54535', navigationBarTitleText: '果园好物', navigationBarTextStyle: 'white' },
  tabBar: { color: '#666666', selectedColor: '#c54535', list: [
    { pagePath: 'pages/index/index', text: '首页' },
    { pagePath: 'pages/categories/index', text: '分类' },
    { pagePath: 'pages/cart/index', text: '购物车' },
    { pagePath: 'pages/orders/index', text: '订单' }
  ] }
})
