(() => {
  const parentOrigin = 'http://127.0.0.1:18080';
  if (window.parent === window || !document.referrer || new URL(document.referrer).origin !== parentOrigin) return;
  // This OTS version serializes PostgreSQL's local, timezone-less point timestamps with a Z suffix.
  const databaseOffset = Number(document.currentScript.dataset.databaseOffsetMinutes || 0) * 60000;

  const positions = new Map();
  const markers = new Map();
  let links = new Map();
  let context;
  let centeredUid;
  let lastStatus = '';
  let connectingFallback = false;

  function send(type, data = {}) {
    window.parent.postMessage({ type: `ops-tak-${type}`, ...data }, parentOrigin);
  }

  function age(position) {
    return Date.now() - position.sourceTime;
  }

  function render() {
    if (!context) return;
    const { map, leaflet } = context;
    for (const [uid, marker] of markers) {
      if (!links.has(uid)) {
        marker.remove();
        markers.delete(uid);
      }
    }
    for (const [uid, link] of links) {
      const position = positions.get(uid);
      if (!position) continue;
      let marker = markers.get(uid);
      if (!marker) {
        const label = document.createElement('span');
        label.textContent = link.label;
        const icon = leaflet.divIcon({
          className: 'ops-linked-marker', html: label, iconSize: [1, 1], iconAnchor: [0, 27],
        });
        marker = leaflet.marker(position.coordinates, { icon, title: link.label, zIndexOffset: 1000 }).addTo(map);
        marker.on('click', () => openTask(uid));
        markers.set(uid, marker);
      }
      marker.setLatLng(position.coordinates);
      marker.getElement()?.classList.toggle('stale', age(position) > 120000);
      marker.getElement()?.classList.toggle('selected', uid === centeredUid);
    }
    const linkedPositions = [...links.keys()].flatMap((uid) => {
      const position = positions.get(uid);
      return position ? [{ uid, timestamp: position.sourceTime, stale: age(position) > 120000 }] : [];
    });
    const status = { connected: context.socket.connected, positions: linkedPositions };
    const signature = JSON.stringify(status);
    if (signature !== lastStatus) {
      lastStatus = signature;
      send('location-status', status);
    }
  }

  function openTask(uid) {
    const tasks = links.get(uid)?.tasks || [];
    if (tasks.length === 1) {
      send('open-task', { ref: tasks[0].ref });
      return;
    }
    const choices = document.createElement('div');
    choices.className = 'ops-map-task-choices';
    for (const task of tasks) {
      const button = document.createElement('button');
      button.textContent = task.title;
      button.addEventListener('click', () => {
        markers.get(uid)?.closePopup();
        send('open-task', { ref: task.ref });
      });
      choices.append(button);
    }
    markers.get(uid)?.bindPopup(choices).openPopup();
  }

  function onPoint(point) {
    const { uid, latitude, longitude, timestamp } = point || {};
    const sourceTime = Date.parse(timestamp) - databaseOffset;
    if (!links.has(uid) || !Number.isFinite(sourceTime) || sourceTime > Date.now() + 60000
        || !Number.isFinite(latitude) || Math.abs(latitude) > 90
        || !Number.isFinite(longitude) || Math.abs(longitude) > 180) return;
    if (positions.has(uid) && positions.get(uid).sourceTime >= sourceTime) return;
    positions.set(uid, { coordinates: [latitude, longitude], sourceTime });
    render();
  }

  function onDevice(device) {
    if (device?.last_point) onPoint({ ...device.last_point, uid: device.uid });
  }

  async function hydrate() {
    if (!context || !links.size) return;
    await Promise.allSettled([...links.keys()].map(async (uid) => {
      const params = new URLSearchParams({
        sender_uid: uid, sort_by: 'timestamp', sort_direction: 'desc', per_page: '8',
      });
      const response = await fetch(`/api/cot?${params}`, { cache: 'no-store', signal: AbortSignal.timeout(8000) });
      if (!response.ok) return;
      const { results } = await response.json();
      const latest = results.find((entry) => entry.uid === uid && entry.point);
      if (latest) onPoint(latest.point);
    }));
  }

  function connect(detail) {
    if (context?.map === detail.map) return;
    if (context) {
      context.socket.off('point', onPoint);
      context.socket.off('eud', onDevice);
      context.socket.off('connect', onConnect);
      context.socket.off('disconnect', render);
      for (const marker of markers.values()) marker.remove();
      markers.clear();
    }
    context = detail;
    context.socket.on('point', onPoint);
    context.socket.on('eud', onDevice);
    context.socket.on('connect', onConnect);
    context.socket.on('disconnect', render);
    send('task-links-ready');
    onConnect();
  }

  window.addEventListener('ops-tak-map-context', ({ detail }) => connect(detail));

  function mountedMap(container) {
    const key = Object.keys(container).find((name) => name.startsWith('__reactFiber$'));
    for (let node = container[key]; node; node = node.return) {
      let hook = node.memoizedState;
      for (let index = 0; hook && index < 20; index++, hook = hook.next) {
        const value = hook.memoizedState;
        const map = [value, value?.current, value?.map]
          .find((item) => item && typeof item.setView === 'function' && typeof item.getCenter === 'function');
        if (map) return map;
      }
    }
    return null;
  }

  // An already-open native page can have loaded the map bundle before the bridge hook was deployed.
  const mounted = new MutationObserver(async () => {
    if (context || connectingFallback) return;
    const container = document.querySelector('.leaflet-container');
    if (!container || typeof window.L?.marker !== 'function') return;
    const map = mountedMap(container);
    if (!map) return;
    connectingFallback = true;
    try {
      const { n: socket } = await import('/assets/js/DefaultLayout-_hfm_neU.js');
      if (!context && typeof socket?.on === 'function') connect({ map, leaflet: window.L, socket });
    } catch {
      // A fresh native map bundle can still initialize the explicit bridge hook.
    } finally {
      mounted.disconnect();
    }
  });
  mounted.observe(document.body, { childList: true, subtree: true });

  function onConnect() {
    render();
    hydrate();
  }

  window.addEventListener('message', ({ origin, source, data }) => {
    if (origin !== parentOrigin || source !== window.parent || !data) return;
    if (data.type === 'ops-tak-bind-tasks') {
      const next = new Map(data.links.map((link) => [link.uid, link]));
      for (const uid of positions.keys()) if (!next.has(uid)) positions.delete(uid);
      links = next;
      render();
      hydrate();
    }
    if (data.type === 'ops-tak-focus') {
      if (!context || !links.has(data.uid) || !positions.has(data.uid)) {
        send('focus-missing', { uid: data.uid });
        return;
      }
      centeredUid = data.uid;
      context.map.setView(positions.get(data.uid).coordinates, Math.max(context.map.getZoom(), 14));
      render();
      send('focused', { uid: data.uid, stale: age(positions.get(data.uid)) > 120000 });
    }
  });

  setInterval(render, 5000);
})();
