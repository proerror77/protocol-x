(() => {
  if (window.top !== window) return;
  document.documentElement.classList.add('golf-tak-admin');

  const groups = [
    ['值班', [
      ['/dashboard', '管理总览'],
      ['/map', '实时地图'],
      ['/euds', '在线设备与登记'],
      ['/alerts', '告警'],
      ['/missions', '任务共享'],
    ]],
    ['部署与权限', [
      ['/data_packages', '地图包与资料'],
      ['/groups', '群组与频道'],
      ['/users', '用户与权限'],
      ['/device_profiles', '终端配置'],
      ['/server_plugin_manager', '服务端插件'],
      ['/plugin_updates', '客户端插件分发'],
      ['/jobs', '定时任务'],
    ]],
  ];
  const cards = [
    ['/map', '实时地图', '查看来自 TAK 的设备、点位和态势。'],
    ['/euds', '设备和登记', '查找终端、连接记录和证书登记入口。'],
    ['/groups', '群组与频道', '管理人员和数据源收发权限。'],
    ['/data_packages', '地图包与资料', '查看已上传的地图、点位和任务资料。'],
    ['/server_plugin_manager', '服务端插件', '查看 AIS 等插件的配置与启用状态。'],
    ['/plugin_updates', '客户端分发', '管理面向 ATAK 的插件版本。'],
  ];
  const airport = 'https://www.hongkongairport.com/en/flights/arrivals/passenger.page';
  const flightUrl = location.origin === 'http://127.0.0.1:18081'
    ? 'http://127.0.0.1:18080/?view=flights' : airport;
  let currentMain;
  let currentSidebar;
  let sidebarSignature = '';

  function element(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text) item.textContent = text;
    return item;
  }

  function anchor(path, label, className = '') {
    const item = element('a', className, label);
    item.href = path;
    if (/^https?:/.test(path)) {
      item.target = '_blank';
      item.rel = 'noopener';
    }
    return item;
  }

  function nativeRoutes(sidebar) {
    return new Map([...sidebar.querySelectorAll('a[href]')]
      .filter((link) => !link.closest('.golf-admin-menu'))
      .map((link) => [new URL(link.href).pathname, link]));
  }

  function buildNavigation(sidebar, routes) {
    const signature = JSON.stringify([...routes.keys()]);
    if (sidebar === currentSidebar && signature === sidebarSignature) return;
    currentSidebar = sidebar;
    sidebarSignature = signature;
    sidebar.querySelector('.golf-admin-menu')?.remove();
    const menu = element('nav', 'golf-admin-menu');
    menu.setAttribute('aria-label', 'TAK 管理导航');
    for (const [label, entries] of groups) {
      const group = element('section');
      group.append(element('h2', '', label));
      for (const [path, title] of entries) {
        if (!routes.has(path)) continue;
        group.append(anchor(path, title, 'golf-admin-route'));
        routes.get(path).classList.add('golf-admin-replaced-link');
      }
      if (label === '值班') group.append(anchor(flightUrl, '香港航班到港 ↗', 'golf-admin-route'));
      if (group.children.length > 1) menu.append(group);
    }
    sidebar.prepend(menu);
  }

  function dashboard(main, routes) {
    const visible = ['/dashboard', '/'].includes(location.pathname);
    if (!visible) {
      main.querySelector('.golf-admin-home')?.remove();
      return;
    }
    if (currentMain === main && main.querySelector('.golf-admin-home')) return;
    currentMain = main;
    const home = element('section', 'golf-admin-home');
    const header = element('div', 'golf-admin-hero');
    const introduction = element('div');
    introduction.append(element('span', 'golf-admin-site', 'HKG / 香港试点'),
      element('h1', '', 'TAK 管理总览'),
      element('p', '', '从地图、终端和资源入口处理现场工作。'));
    const source = element('div', 'golf-admin-hero-status');
    const service = element('strong', 'golf-admin-service', '正在检查 TAK 服务');
    source.append(service, anchor(flightUrl, '查看香港客运到港 ↗', 'golf-admin-flight-action'));
    header.append(introduction, source);
    const modules = element('div', 'golf-admin-shortcuts');
    for (const [path, title, description] of cards) {
      if (!routes.has(path)) continue;
      const card = anchor(path, '', 'golf-admin-shortcut');
      card.append(element('strong', '', title), element('span', '', description));
      modules.append(card);
    }
    home.append(header, modules);
    main.prepend(home);
    check(service);
  }

  async function check(service) {
    if (!service || service.dataset.pending === 'true') return;
    service.dataset.pending = 'true';
    try {
      const response = await fetch('/api/status', { cache: 'no-store', signal: AbortSignal.timeout(8000) });
      service.textContent = response.ok ? 'TAK 服务可用' : response.status === 401 ? '请重新登录' : 'TAK 服务检查失败';
      service.dataset.state = response.ok ? 'ready' : 'failed';
    } catch {
      service.textContent = 'TAK 服务暂不可达';
      service.dataset.state = 'failed';
    } finally {
      delete service.dataset.pending;
    }
  }

  let scheduled = false;
  function update() {
    scheduled = false;
    const header = document.querySelector('.mantine-AppShell-header');
    const sidebar = document.querySelector('.mantine-AppShell-navbar');
    const main = document.querySelector('.mantine-AppShell-main');
    if (!header || !sidebar || !main) return;
    const logo = header.querySelector('img');
    if (logo && !header.querySelector('.golf-admin-wordmark')) {
      logo.classList.add('golf-admin-original-mark');
      const wordmark = element('span', 'golf-admin-wordmark', 'TAK 管理台');
      wordmark.prepend(element('span', 'golf-admin-mark', 'HK'));
      logo.after(wordmark);
    }
    const native = nativeRoutes(sidebar);
    if (!native.has('/map')) return;
    buildNavigation(sidebar, native);
    const path = location.pathname === '/' ? '/dashboard' : location.pathname;
    for (const link of sidebar.querySelectorAll('.golf-admin-menu a')) {
      const active = link.pathname === path && link.origin === location.origin;
      if (active && !link.hasAttribute('aria-current')) link.setAttribute('aria-current', 'page');
      if (!active && link.hasAttribute('aria-current')) link.removeAttribute('aria-current');
    }
    dashboard(main, native);
  }

  new MutationObserver(() => {
    if (!scheduled) {
      scheduled = true;
      requestAnimationFrame(update);
    }
  }).observe(document.body, { childList: true, subtree: true });
  window.addEventListener('popstate', update);
  setInterval(() => { if (!document.hidden) check(document.querySelector('.golf-admin-service')); }, 30000);
  update();
})();
