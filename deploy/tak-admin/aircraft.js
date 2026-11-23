(() => {
  const AIRCRAFT_TYPES = ['a-n-A-C-F', 'a-n-A-C'];
  const FEED_TAG = 'adsbcot-opentak-hk-pytak';
  const AIRPORT = [22.3089, 113.9147];
  const OFFICIAL_ARRIVALS = 'https://www.hongkongairport.com/en/flights/arrivals/passenger.page';
  const OPS_ORIGIN = 'http://127.0.0.1:18080';
  const labels = { scheduled: '计划', estimated: '有预计', landed: '已落地', at_gate: '已到达机位' };
  const clock = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  });
  const parser = new DOMParser();
  let current;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function decode(row, now) {
    if (!AIRCRAFT_TYPES.includes(row.type) || !/^ICAO-[\da-f]{6}$/i.test(row.uid) || typeof row.xml !== 'string') return null;
    const event = parser.parseFromString(row.xml, 'application/xml').documentElement;
    if (event.tagName !== 'event' || event.getAttribute('uid') !== row.uid
        || !event.querySelector('detail > _flow-tags_')?.hasAttribute(FEED_TAG)) return null;
    const point = event.querySelector('point');
    const latitude = Number(point?.getAttribute('lat') ?? NaN);
    const longitude = Number(point?.getAttribute('lon') ?? NaN);
    const observed = Date.parse(event.getAttribute('time'));
    const expires = Date.parse(event.getAttribute('stale'));
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180 || !Number.isFinite(observed) || !Number.isFinite(expires)
        || observed > now + 60000 || now - observed > 360000) return null;
    const ident = event.querySelector('contact')?.getAttribute('callsign')?.split(/[-\s]/)[0]?.toUpperCase();
    if (!ident || !/^[A-Z]{3}\d[A-Z0-9]{0,4}$/.test(ident)) return null;
    const altitude = Number(point?.getAttribute('hae') ?? NaN);
    return { uid: row.uid, ident, coordinates: [latitude, longitude], observed, expires, altitude };
  }

  function linkedFlight(state, track) {
    if (state.arrivals?.state !== 'current' ||
        state.tracks.filter(({ ident }) => ident === track.ident).length !== 1) return null;
    const row = state.arrivals.arrivals.find(({ callsign }) => callsign === track.ident);
    const seen = track.observed / 1000;
    return row?.track_window && seen >= row.track_window[0] && seen <= row.track_window[1] ? row : null;
  }

  function notifyParent(type, payload) {
    if (window.parent !== window) window.parent.postMessage({ type, ...payload }, OPS_ORIGIN);
  }

  function popup(track, stale, arrival, sourceTime) {
    const body = element('div', 'golf-aircraft-popup');
    body.append(element('strong', '', track.ident),
      element('div', '', `ADS-B · ${stale ? '上次位置' : '接收位置'} ${clock.format(track.observed)}`));
    if (Number.isFinite(track.altitude) && Math.abs(track.altitude) < 30000) {
      body.append(element('div', '', `报告高度 ${Math.round(track.altitude)} 米`));
    }
    if (arrival) {
      body.append(element('strong', '', `${arrival.flights.join(' / ')} · 香港到港`),
        element('div', '', `状态：${labels[arrival.status]} · 计划 ${arrival.scheduled || '—'}`),
        element('div', '', `预计：${arrival.estimated || '机场未提供'}`),
        element('div', '', `实际：${arrival.actual || '机场未确认'}`),
        element('small', '', `香港机场 · ${clock.format(new Date(sourceTime))}（香港时间）`));
    } else {
      body.append(element('div', '', '暂无可确认的香港客运到港记录'));
    }
    const board = element('a', '', '查看香港机场到港资讯 ↗');
    board.href = OFFICIAL_ARRIVALS;
    board.target = '_blank';
    board.rel = 'noopener';
    body.append(board);
    return body;
  }

  function redraw(state) {
    if (!state.enabled) {
      state.status.textContent = '已隐藏';
      return;
    }
    const now = Date.now();
    const latest = Math.max(...state.tracks.map(({ observed }) => observed));
    const kept = new Set();
    for (const track of state.tracks) {
      if (now - track.observed > 360000) continue;
      kept.add(track.uid);
      const stale = now > track.expires;
      const arrival = linkedFlight(state, track);
      const airportTime = state.arrivals?.source_updated_at;
      const version = JSON.stringify([track.observed, stale, arrival, airportTime]);
      let marker = state.markers.get(track.uid);
      if (!marker) {
        const label = element('span', '', `✈ ${track.ident}`);
        const icon = state.leaflet.divIcon({ className: 'golf-aircraft-icon', html: label,
          iconSize: [1, 1], iconAnchor: [0, 0] });
        marker = state.leaflet.marker(track.coordinates, { icon, title: track.ident, zIndexOffset: 350 });
        marker.bindPopup(popup(track, stale, arrival, airportTime));
        marker.on('popupopen', () => {
          const currentTrack = state.tracks.find(({ uid }) => uid === track.uid);
          if (currentTrack && linkedFlight(state, currentTrack)) {
            notifyParent('ops-tak-flight-selected', { callsign: currentTrack.ident });
          }
        });
        marker.golfAircraftVersion = version;
        marker.addTo(state.layer);
        state.markers.set(track.uid, marker);
      } else {
        if (marker.golfAircraftVersion !== version) {
          marker.setLatLng(track.coordinates);
          marker.setPopupContent(popup(track, stale, arrival, airportTime));
          marker.golfAircraftVersion = version;
        }
      }
      marker.getElement()?.classList.toggle('golf-aircraft-stale', stale);
    }
    for (const [uid, marker] of state.markers) {
      if (!kept.has(uid)) {
        state.layer.removeLayer(marker);
        state.markers.delete(uid);
      }
    }
    if (state.tracks.length && kept.size) {
      const fresh = state.tracks.filter((track) => track.expires >= now).length;
      state.status.textContent = `${kept.size} 架 · ${fresh && !state.failed ? '更新' : '最后位置'} ${clock.format(latest)}`;
    } else {
      state.status.textContent = state.failed ? '航迹暂不可用' : '等待香港航迹';
    }
  }

  function refreshArrivals(state) {
    if (state.arrivalsRequest) return state.arrivalsRequest;
    state.arrivalsRequest = (async () => {
      try {
        const response = await fetch('/golf-tak-admin/flight-arrivals',
          { cache: 'no-store', signal: AbortSignal.timeout(12000) });
        if (!response.ok) throw new Error(String(response.status));
        state.arrivals = await response.json();
      } catch {
        state.arrivals = null;
      } finally {
        state.arrivalsRequest = null;
        if (current === state) redraw(state);
      }
    })();
    return state.arrivalsRequest;
  }

  function refresh(state) {
    if (state.tracksRequest) return state.tracksRequest;
    if (document.hidden || !state.enabled) return;
    state.tracksRequest = (async () => {
      try {
        const responses = await Promise.all(AIRCRAFT_TYPES.map(async (type) => {
          const query = new URLSearchParams({ type, sort_by: 'timestamp', sort_direction: 'desc', per_page: '70' });
          const response = await fetch(`/api/cot?${query}`, { cache: 'no-store', signal: AbortSignal.timeout(8000) });
          if (!response.ok) throw new Error(String(response.status));
          return (await response.json()).results;
        }));
        const now = Date.now();
        const latest = new Map();
        for (const row of responses.flat()) {
          const position = decode(row, now);
          if (!position) continue;
          if ((latest.get(position.uid)?.observed || 0) < position.observed) latest.set(position.uid, position);
        }
        state.tracks = [...latest.values()];
        state.failed = false;
      } catch {
        state.failed = true;
      } finally {
        state.tracksRequest = null;
        if (current === state) redraw(state);
      }
    })();
    return state.tracksRequest;
  }

  function attach(map, leaflet) {
    if (current?.map === map || location.pathname !== '/map') return;
    if (current) current.destroy();
    const status = element('span', 'golf-aircraft-status', '正在读取香港航迹');
    const layer = leaflet.layerGroup().addTo(map);
    const state = { map, leaflet, status, layer, markers: new Map(), tracks: [], enabled: true };
    const panel = leaflet.control({ position: 'bottomright' });
    panel.onAdd = () => {
      const box = element('div', 'golf-aircraft-control');
      const toggle = element('button', '', 'ADS-B 航迹');
      toggle.type = 'button';
      toggle.setAttribute('aria-pressed', 'true');
      toggle.addEventListener('click', () => {
        state.enabled = !state.enabled;
        toggle.setAttribute('aria-pressed', String(state.enabled));
        if (state.enabled) {
          layer.addTo(map);
          redraw(state);
          refresh(state);
        } else {
          layer.remove();
          redraw(state);
        }
      });
      const focus = element('button', '', '香港空域');
      focus.type = 'button';
      focus.addEventListener('click', () => {
        const [lat, lon] = AIRPORT;
        map.fitBounds([[lat - .42, lon - .46], [lat + .42, lon + .46]], { padding: [12, 20], maxZoom: 11 });
      });
      box.append(toggle, focus, status);
      leaflet.DomEvent.disableClickPropagation(box);
      leaflet.DomEvent.disableScrollPropagation(box);
      return box;
    };
    panel.addTo(map);
    current = state;
    const poll = setInterval(() => refresh(state), 20000);
    const arrivalsPoll = setInterval(() => {
      if (!document.hidden) refreshArrivals(state);
    }, 90000);
    const aging = setInterval(() => redraw(state), 5000);
    const resume = () => { if (!document.hidden) { refresh(state); refreshArrivals(state); } };
    document.addEventListener('visibilitychange', resume);
    const focusFlight = async ({ origin, source, data }) => {
      if (origin !== OPS_ORIGIN || source !== window.parent || data?.type !== 'ops-tak-focus-flight'
          || !/^[A-Z]{3}\d[A-Z0-9]{0,4}$/.test(data.callsign)) return;
      const { request } = data;
      state.focusRequest = request;
      if (!state.enabled) state.toggle.click();
      await Promise.all([
        state.arrivals?.state === 'current' ? state.arrivalsRequest : refreshArrivals(state),
        state.tracksRequest || (!state.tracks.length && refresh(state)),
      ]);
      if (current !== state || request !== state.focusRequest) return;
      const track = state.tracks.find((item) => item.ident === data.callsign && Date.now() - item.observed <= 360000);
      let status = state.arrivals?.state === 'current' ? 'missing' : 'unavailable';
      if (track && linkedFlight(state, track)) {
        redraw(state);
        const marker = state.markers.get(track.uid);
        if (marker) {
          map.setView(track.coordinates, Math.max(map.getZoom(), 12));
          marker.openPopup();
          status = Date.now() > track.expires ? 'stale' : 'found';
        }
      }
      notifyParent('ops-tak-flight-result', { request, status });
    };
    state.toggle = panel.getContainer().querySelector('button');
    window.addEventListener('message', focusFlight);
    state.destroy = () => {
      window.dispatchEvent(new CustomEvent('golf-tak-map-unmounted', { detail: { map } }));
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('message', focusFlight);
      clearInterval(poll);
      clearInterval(arrivalsPoll);
      clearInterval(aging);
      panel.remove();
      layer.remove();
    };
    window.dispatchEvent(new CustomEvent('golf-tak-map-mounted', { detail: { map, leaflet } }));
    notifyParent('ops-tak-flight-map-ready', {});
    refreshArrivals(state);
    refresh(state);
  }

  function existingMap(container) {
    const key = Object.keys(container).find((name) => name.startsWith('__reactFiber$'));
    for (let node = container[key]; node; node = node.return) {
      let hook = node.memoizedState;
      for (let index = 0; hook && index < 20; index++, hook = hook.next) {
        const value = hook.memoizedState;
        const map = [value, value?.current, value?.map]
          .find((item) => typeof item?.getCenter === 'function' && typeof item?.addLayer === 'function');
        if (map) return map;
      }
    }
    return null;
  }

  function reconcile() {
    const container = document.querySelector('.leaflet-container');
    if (location.pathname !== '/map' || !container) {
      current?.destroy();
      current = null;
      return;
    }
    if (current?.map?.getContainer() === container || !window.L?.layerGroup) return;
    const map = existingMap(container);
    if (map) attach(map, window.L);
  }

  window.addEventListener('ops-tak-map-context', ({ detail }) => attach(detail.map, detail.leaflet));
  new MutationObserver(reconcile).observe(document.body, { childList: true, subtree: true });
  reconcile();
})();
