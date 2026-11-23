import { focusTakFlight } from './live.js?v=flights-map-1';

const statusNames = {
  scheduled: '计划', estimated: '有预计', landed: '已落地', at_gate: '已到达机位',
  delayed: '机场通报延误', cancelled: '取消', other: '请看机场看板',
};

const clock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});
const $ = (id) => document.getElementById(id);
const lookup = (value) => String(value || '').toUpperCase().replace(/\s+/g, '');
export const hasHongKongArrivals = (eventId) => ['golf-demo', 'golf-hkg-dispatch-demo', 'golf-hkg-arrivals-demo'].includes(eventId);

export function createFlightBoard(request, canBind = () => false) {
  let snapshot;
  let requestInFlight;
  let lastAttempt = 0;
  let limit = 30;
  let failed = false;
  let activeEvent;
  let selectedCallsign = '';

  function render() {
    const body = $('flight-arrivals');
    const search = lookup($('flight-search').value);
    const filter = $('flight-filter').value;
    body.replaceChildren();
    if (!snapshot) {
      $('flight-summary').textContent = failed ? '来源暂不可用。请打开机场官方到港看板。' : '正在读取香港机场数据…';
      $('flight-more').hidden = true;
      return;
    }
    const time = clock.format(new Date(snapshot.source_updated_at));
    const fresh = snapshot.state === 'current' && !failed;
    $('flight-date').textContent = `${snapshot.date} · 香港时间`;
    $('flight-summary').textContent =
      `${fresh ? '机场数据已同步' : '显示上次成功读取的数据'} · 来源更新 ${time}`;
    $('flight-summary').classList.toggle('flight-stale', !fresh);
    const currentTime = clock.format(new Date()).slice(0, 5);
    const rows = snapshot.arrivals
      .filter((row) => (!filter || row.status === filter)
        && (!search || [...row.flights, ...row.origins].some((value) => lookup(value).includes(search))))
      .sort((a, b) => {
        if (search) return (a.scheduled || '').localeCompare(b.scheduled || '');
        const rank = (row) => (row.scheduled || '') >= currentTime ? 0 : 1;
        return rank(a) - rank(b) || (rank(a) ? -1 : 1) * (a.scheduled || '').localeCompare(b.scheduled || '');
      });
    $('flight-count').textContent = `显示 ${Math.min(limit, rows.length)} / ${rows.length} 班`;
    $('flight-more').hidden = rows.length <= limit;
    if (!rows.length) {
      const cell = body.insertRow().insertCell();
      cell.colSpan = 7;
      cell.textContent = '没有匹配的航班';
    }
    for (const flight of rows.slice(0, limit)) {
      const row = body.insertRow();
      row.classList.toggle('flight-selected', Boolean(selectedCallsign && flight.callsign === selectedCallsign));
      const numbers = row.insertCell();
      for (const number of flight.flights) {
        const item = document.createElement(flight.callsign && fresh ? 'button' : 'span');
        item.textContent = number;
        if (item.tagName === 'BUTTON') {
          item.type = 'button';
          item.className = 'flight-locate';
          item.setAttribute('aria-label', `定位 ${number}`);
          item.addEventListener('click', () => {
            selectedCallsign = flight.callsign;
            $('flight-focus-message').textContent = focusTakFlight(flight.callsign);
            for (const tr of body.rows) tr.classList.toggle('flight-selected', tr === row);
          });
        }
        numbers.append(item, ' ');
      }
      for (const value of [flight.origins.join(' · '), flight.scheduled, flight.estimated, flight.actual,
        statusNames[flight.status], [flight.arrivals_hall, flight.baggage_belt].filter(Boolean).join(' / ')]) {
        row.insertCell().textContent = value || '—';
      }
      row.cells[0].title = flight.flights.join(' / ');
      row.cells[5].dataset.status = flight.status;
      if (fresh && canBind()) {
        const bind = document.createElement('button');
        bind.type = 'button';
        bind.className = 'flight-locate pickup-add';
        bind.textContent = '关联接机';
        bind.setAttribute('aria-label', `关联 ${flight.flights[0]} 到接机任务`);
        bind.addEventListener('click', () => document.dispatchEvent(new CustomEvent('ops-pickup-bind-flight',
          { detail: { flight: flight.flights[0], date: snapshot.date } })));
        const reception = document.createElement('button');
        reception.type = 'button';
        reception.className = bind.className;
        reception.textContent = '安排球员接待';
        reception.setAttribute('aria-label', `安排 ${flight.flights[0]} 球员接待`);
        reception.addEventListener('click', () => document.dispatchEvent(new CustomEvent('ops-arrival-create',
          { detail: { flight: flight.flights[0], date: snapshot.date } })));
        numbers.append(bind, reception);
      }
    }
  }

  function resetFilter() {
    limit = 30;
    render();
  }

  $('flight-search').addEventListener('input', resetFilter);
  $('flight-filter').addEventListener('change', resetFilter);
  document.addEventListener('ops-flight-selected', (event) => {
    if (!snapshot?.arrivals.some(({ callsign }) => callsign === event.detail)) return;
    selectedCallsign = event.detail;
    if (!$('flight-board').hidden) render();
  });
  $('flight-more').addEventListener('click', () => {
    limit += 30;
    render();
  });

  async function refresh(eventId, force = false) {
    if (!hasHongKongArrivals(eventId) || requestInFlight
        || (!force && activeEvent === eventId && Date.now() - lastAttempt < 45000)) return;
    activeEvent = eventId;
    lastAttempt = Date.now();
    $('flight-refresh').disabled = true;
    requestInFlight = request(`/api/events/${eventId}/flight-arrivals`, { signal: AbortSignal.timeout(14000) });
    try {
      snapshot = await requestInFlight;
      failed = false;
    } catch {
      failed = true;
    } finally {
      requestInFlight = null;
      $('flight-refresh').disabled = false;
      render();
    }
  }

  $('flight-refresh').addEventListener('click', () => refresh(activeEvent, true));
  return { refresh };
}
