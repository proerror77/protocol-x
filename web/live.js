const localOps = 'http://127.0.0.1:18080';
const localTak = 'http://127.0.0.1:18081';
const frame = document.querySelector('#native-tak-frame');
const mapPanel = document.querySelector('#native-tak-panel');
const mapStatus = document.querySelector('#native-tak-status');
const desk = document.querySelector('#live-desk');
const feed = document.querySelector('#live-feed');
const locations = document.querySelector('#tak-location-status');
const focusFeedback = document.querySelector('#tak-focus-feedback');
const demoVehicles = {
  'car-02': { uid: 'golf-hk-car-02', label: '演练 · 02 号接驳车' },
  'car-03': { uid: 'golf-hk-car-03', label: '演练 · 03 号备用车' },
  'car-05': { uid: 'golf-hk-car-05', label: '演练 · 05 号接驳车' },
};
const demoStaff = {
  'demo-staff': { uid: 'golf-hk-staff-a', label: '演练 · 接待员 A' },
  'demo-other': { uid: 'golf-hk-staff-b', label: '演练 · 接待员 B' },
};
let mapTimeout;
let mapLoaded = false;
let lastFeed = '';
let bridgeReady = false;
let activeLinks = [];
let sentLinks = '';
let flightMapReady = false;
let flightRequest = 0;

export function mapEnabled({ mode, ptt_event_id } = {}, eventId) {
  return mode === 'pilot' && location.origin === localOps && ptt_event_id === eventId;
}

window.addEventListener('message', (event) => {
  if (event.origin !== localTak || event.source !== frame.contentWindow) return;
  const { data } = event;
  if (data?.type === 'ops-tak-flight-map-ready') flightMapReady = true;
  if (data?.type === 'ops-tak-flight-result' && data.request === flightRequest) {
    const labels = {
      found: '已定位飞机并打开到港详情。',
      stale: '已定位最后已知位置，航迹已陈旧。',
      missing: '暂未找到可确认的香港到港航迹。',
      unavailable: '机场到港数据暂不可用，无法确认匹配。',
    };
    if (Object.hasOwn(labels, data.status)) focusFeedback.textContent = labels[data.status];
  }
  if (data?.type === 'ops-tak-flight-selected' && /^[A-Z]{3}\d[A-Z0-9]{0,4}$/.test(data.callsign)) {
    document.dispatchEvent(new CustomEvent('ops-flight-selected', { detail: data.callsign }));
  }
  if (data?.type === 'ops-tak-task-links-ready') {
    bridgeReady = true;
    sentLinks = '';
    sendLinks();
  }
  if (data?.type === 'ops-tak-location-status') {
    const recent = data.positions.filter((position) => !position.stale);
    const latest = Math.max(...data.positions.map(({ timestamp }) => timestamp));
    locations.textContent = data.connected
      ? recent.length ? `${recent.length} 个演练点位 · ${new Date(latest).toLocaleTimeString('zh-CN')}`
        : data.positions.length ? '演练位置已陈旧' : '等待演练位置'
      : 'TAK 位置连接中断';
  }
  if (data?.type === 'ops-tak-open-task' &&
      activeLinks.some((link) => link.tasks.some((task) => task.ref === data.ref))) {
    document.dispatchEvent(new CustomEvent('ops-map-open-task', { detail: data.ref }));
  }
  if (data?.type === 'ops-tak-focused' || data?.type === 'ops-tak-focus-missing') {
    const link = activeLinks.find(({ uid }) => uid === data.uid);
    if (link) {
      focusFeedback.textContent = data.type === 'ops-tak-focus-missing'
        ? `${link.label} · 尚无位置`
        : `${link.label} · ${data.stale ? '最后已知位置，已陈旧' : '已定位'}`;
    }
  }
  if (event.data?.type !== 'ops-native-tak-map') return;
  const labels = { login: '请登录 TAK', open: '原生地图已打开', loading: '正在加载地图' };
  if (!Object.hasOwn(labels, event.data.status)) return;
  if (event.data.status === 'login') flightMapReady = false;
  clearTimeout(mapTimeout);
  mapStatus.textContent = labels[event.data.status];
});

export function taskLocations(task, eventId) {
  if (eventId !== 'golf-demo' || task.ref.ontology_id !== 'golf.v1') return [];
  return [
    [demoVehicles[task.resource_ref?.id], '车辆'],
    [demoStaff[task.owner], '人员'],
  ].filter(([location]) => location).map(([location, kind]) => ({ ...location, kind }));
}

function sendLinks() {
  const signature = JSON.stringify(activeLinks);
  if (!bridgeReady || signature === sentLinks) return;
  frame.contentWindow.postMessage({ type: 'ops-tak-bind-tasks', links: activeLinks }, localTak);
  sentLinks = signature;
}

export function focusTakLocation(uid) {
  if (!activeLinks.some((link) => link.uid === uid)) return;
  focusFeedback.textContent = bridgeReady ? '正在定位' : '请先连接 TAK 地图';
  if (bridgeReady) frame.contentWindow.postMessage({ type: 'ops-tak-focus', uid }, localTak);
  mapPanel.scrollIntoView({ block: 'nearest' });
}

export function focusTakFlight(callsign) {
  if (desk.hidden || mapPanel.hidden) return '当前入口没有连接 TAK 地图。';
  if (!flightMapReady) {
    mapPanel.scrollIntoView({ block: 'nearest' });
    return '请先登录或等待 TAK 地图加载。';
  }
  focusFeedback.textContent = '正在核对航班呼号和航迹…';
  frame.contentWindow.postMessage({ type: 'ops-tak-focus-flight', callsign, request: ++flightRequest }, localTak);
  mapPanel.scrollIntoView({ block: 'nearest' });
  return '';
}

export function updateLive(bootstrap, eventId, view, data) {
  const enabled = Boolean(data && mapEnabled(bootstrap, eventId));
  const byUid = new Map();
  if (enabled && view === 'overview') {
    for (const task of data.tasks) {
      const ref = [task.ref.ontology_id, task.ref.object_type, task.ref.id].join('/');
      for (const location of taskLocations(task, eventId)) {
        if (!byUid.has(location.uid)) byUid.set(location.uid, { ...location, tasks: [] });
        byUid.get(location.uid).tasks.push({ ref, title: task.title });
      }
    }
  }
  activeLinks = [...byUid.values()];
  sendLinks();
  const showingFlights = view === 'flights';
  desk.hidden = (view !== 'overview' && !showingFlights) || !data || (showingFlights && !enabled);
  feed.closest('.live-rail').hidden = showingFlights;
  locations.hidden = showingFlights;
  if (desk.hidden) return;
  mapPanel.hidden = !enabled;
  desk.classList.toggle('has-map', enabled && !showingFlights);
  if (enabled && !mapLoaded) {
    mapLoaded = true;
    frame.src = `${localTak}/map?view=task-links`;
    mapTimeout = setTimeout(() => {
      mapStatus.textContent = '未收到地图响应，可单独打开';
    }, 8000);
  }

  if (showingFlights) return;
  const entries = [
    ...data.messages.map(({ id, created_at, text }) => ({ id, created_at, text, view: 'communication', label: '团队汇报' })),
    ...data.receipts.map(({ id, created_at, result }) => ({
      id, created_at, text: result?.note || '系统已提交行动', view: 'receipts', label: '行动回执',
    })),
  ].sort((a, b) => new Date(b.created_at) - new Date(a.created_at)).slice(0, 7);
  const signature = JSON.stringify(entries);
  if (signature === lastFeed) return;
  lastFeed = signature;
  feed.replaceChildren();
  if (!entries.length) {
    const empty = document.createElement('li');
    empty.textContent = '当前活动还没有汇报或行动回执';
    feed.append(empty);
    return;
  }
  for (const entry of entries) {
    const row = document.createElement('li');
    const time = document.createElement('time');
    time.dateTime = entry.created_at;
    const timeLabel = new Date(entry.created_at).toLocaleTimeString('zh-CN', {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
    time.textContent = `${timeLabel} · ${entry.label}`;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = entry.text.length > 110 ? `${entry.text.slice(0, 110)}…` : entry.text;
    button.addEventListener('click', () => document.querySelector(`[data-view="${entry.view}"]`).click());
    row.append(time, button);
    feed.append(row);
  }
}
