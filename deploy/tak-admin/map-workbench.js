(() => {
  const maxFile = 256 * 1024;
  const maxLayers = 16;
  const maxFeatures = 400;
  const colors = ['#0c766b', '#1457aa', '#a74b17', '#783e9a'];
  const coordinateDepth = new Map([
    ['Point', 0], ['MultiPoint', 1], ['LineString', 1], ['MultiLineString', 2],
    ['Polygon', 2], ['MultiPolygon', 3],
  ]);
  let mounted;

  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
  }

  function button(text, action) {
    const node = element('button', text);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
  }

  function title(value) {
    return typeof value === 'string' ? value.trim().slice(0, 64) : '';
  }

  function position(value) {
    return Array.isArray(value) && value.length >= 2 && value.length <= 3
      && value.every(Number.isFinite) && Math.abs(value[0]) <= 180 && Math.abs(value[1]) <= 90;
  }

  function features(collection) {
    const rows = collection?.type === 'FeatureCollection' ? collection.features
      : collection?.type === 'Feature' ? [collection] : null;
    if (!Array.isArray(rows) || !rows.length || rows.length > maxFeatures) throw new Error('请选择含点、线或面的 GeoJSON 文件');
    let remaining = 5000;
    const validCoordinates = (coords, depth) => depth === 0
      ? --remaining >= 0 && position(coords)
      : Array.isArray(coords) && coords.length > 0 && coords.every((item) => validCoordinates(item, depth - 1));
    return rows.map((row) => {
      const geometry = row?.geometry;
      if (row?.type !== 'Feature' || !coordinateDepth.has(geometry?.type)
        || !validCoordinates(geometry.coordinates, coordinateDepth.get(geometry.type))) {
        throw new Error('GeoJSON 坐标无效或图层过大');
      }
      return {
        type: 'Feature', geometry: { type: geometry.type, coordinates: geometry.coordinates },
        properties: { name: title(row.properties?.name || row.properties?.title) },
      };
    });
  }

  async function attach(map, leaflet) {
    if (mounted?.map === map || !/^\/map\/?$/.test(location.pathname)) return;
    mounted?.destroy();
    const states = leaflet.layerGroup().addTo(map);
    const control = leaflet.control({ position: 'topright' });
    const state = { map, destroy };
    const controller = new AbortController();
    const box = element('section', null, 'tak-workbench');
    box.setAttribute('aria-label', '地图标注与图层');
    const bar = element('div', null, 'tak-workbench-bar');
    const status = element('p', '', 'tak-workbench-status');
    status.setAttribute('role', 'status');
    status.hidden = true;
    const panel = element('div', null, 'tak-workbench-panel');
    panel.hidden = true;
    const layerList = element('div', null, 'tak-workbench-layers');
    const markerForm = element('form', null, 'tak-workbench-panel tak-workbench-form');
    markerForm.hidden = true;
    const placementTools = element('div', null, 'tak-workbench-bar');
    placementTools.hidden = true;
    const mapContainer = map.getContainer();
    let key;
    let layers = [];
    let active;
    let placing = false;
    let draft;
    let waiting = false;

    function say(message) {
      status.textContent = message;
      status.hidden = !message;
    }

    function persisted() {
      const raw = localStorage.getItem(key);
      if (!raw) return [{ id: 'field', name: '现场标记', visible: true, features: [] }];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed) || parsed.length > maxLayers
        || parsed.some((layer) => !layer || typeof layer.id !== 'string' || !title(layer.name)
          || typeof layer.visible !== 'boolean' || !Array.isArray(layer.features))) {
        throw new Error('保存的图层格式无效');
      }
      return parsed;
    }

    async function save(change, success) {
      if (!key || waiting) return false;
      waiting = true;
      const write = () => {
        const next = change(persisted());
        const raw = JSON.stringify(next);
        if (raw.length > 1_048_576 || next.length > maxLayers) throw new Error('已达到本浏览器的图层容量');
        localStorage.setItem(key, raw);
        return next;
      };
      try {
        layers = navigator.locks ? await navigator.locks.request(key, write) : write();
        if (mounted === state) {
          render();
          say(success);
        }
        return true;
      } catch (error) {
        if (mounted === state) say(error instanceof DOMException ? '浏览器无法保存图层' : error.message);
        return false;
      } finally {
        waiting = false;
      }
    }

    const layerToggle = button('图层', () => {
      panel.hidden = !panel.hidden;
      layerToggle.setAttribute('aria-expanded', String(!panel.hidden));
      if (!placing && !draft) say('');
    });
    layerToggle.setAttribute('aria-expanded', 'false');
    const mark = button('标点', () => {
      if (placing || draft) cancelMark();
      else {
        placing = true;
        mapContainer.classList.add('tak-workbench-placing');
        placementTools.hidden = false;
        mark.setAttribute('aria-pressed', 'true');
        say('点击地图放置标记');
      }
    });
    mark.setAttribute('aria-pressed', 'false');
    [mark, layerToggle].forEach((item) => { item.disabled = true; });
    bar.append(mark, layerToggle);

    const newLayer = element('form', null, 'tak-workbench-bar');
    const layerName = element('input');
    layerName.required = true;
    layerName.maxLength = 64;
    layerName.placeholder = '图层名称';
    layerName.setAttribute('aria-label', '新图层名称');
    const createLayer = element('button', '新建图层');
    createLayer.type = 'submit';
    newLayer.append(layerName, createLayer);
    newLayer.addEventListener('submit', async (event) => {
      event.preventDefault();
      const name = title(layerName.value);
      if (!name) return;
      const id = crypto.randomUUID();
      if (await save((all) => [...all, { id, name, visible: true, features: [] }], '图层已创建')) {
        active = id;
        layerName.value = '';
        renderLayerList();
      }
    });

    const importInput = element('input');
    importInput.type = 'file';
    importInput.accept = '.geojson,.json,application/geo+json,application/json';
    importInput.hidden = true;
    importInput.addEventListener('change', async () => {
      const [file] = importInput.files;
      importInput.value = '';
      if (!file) return;
      try {
        if (file.size > maxFile) throw new Error('GeoJSON 文件不能超过 256 KB');
        const imported = features(JSON.parse(await file.text()));
        const name = title(file.name.replace(/\.(geojson|json)$/i, '')) || '导入图层';
        await save((all) => [...all, {
          id: crypto.randomUUID(), name, visible: true, features: imported,
        }], `已导入 ${imported.length} 个地物`);
      } catch (error) {
        say(error instanceof SyntaxError ? '文件不是有效的 GeoJSON' : error.message);
      }
    });
    panel.append(element('h2', '我的图层'), layerList, newLayer,
      button('导入 GeoJSON', () => importInput.click()), importInput,
      element('small', '图层保存在当前浏览器'));

    placementTools.append(button('在地图中心标记', () => pick({ latlng: map.getCenter() })));
    const markerName = element('input');
    markerName.required = true;
    markerName.maxLength = 64;
    markerName.placeholder = '标记名称';
    const nameLabel = element('label', '标记名称');
    nameLabel.append(markerName);
    const markerLayer = element('select');
    const targetLabel = element('label', '保存到');
    targetLabel.append(markerLayer);
    const markerButtons = element('div', null, 'tak-workbench-bar');
    const submit = element('button', '保存标记');
    submit.type = 'submit';
    markerButtons.append(submit, button('取消', cancelMark));
    markerForm.append(nameLabel, targetLabel, markerButtons);
    markerForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      const name = title(markerName.value);
      const layerId = markerLayer.value;
      if (!draft || !name || !layerId) return;
      const coordinates = [draft.lng, draft.lat];
      if (!position(coordinates)) return;
      const feature = { type: 'Feature', geometry: { type: 'Point', coordinates },
        properties: { name, markerId: crypto.randomUUID() } };
      if (await save((all) => {
        const layer = all.find(({ id }) => id === layerId);
        if (!layer) throw new Error('图层已被移除，请重新选择');
        if (layer.features.length >= maxFeatures) throw new Error('此图层已达到地物上限');
        layer.features.push(feature);
        layer.visible = true;
        return all;
      }, '标记已保存')) {
        active = layerId;
        cancelMark(false);
        mark.focus();
      }
    });

    function cancelMark(clearStatus = true) {
      placing = false;
      draft = null;
      mark.setAttribute('aria-pressed', 'false');
      mapContainer.classList.remove('tak-workbench-placing');
      placementTools.hidden = true;
      markerForm.hidden = true;
      if (clearStatus) say('');
    }

    function pick({ latlng }) {
      if (!placing) return;
      placing = false;
      mapContainer.classList.remove('tak-workbench-placing');
      placementTools.hidden = true;
      draft = map.wrapLatLng(latlng);
      markerName.value = '';
      markerForm.hidden = false;
      renderLayerOptions();
      if (layers.some(({ id }) => id === active)) markerLayer.value = active;
      markerName.focus();
      say('');
    }

    function exportLayer(layer) {
      const url = URL.createObjectURL(new Blob([
        JSON.stringify({ type: 'FeatureCollection', features: layer.features }, (name, value) =>
          name === 'markerId' ? undefined : value),
      ], { type: 'application/geo+json' }));
      const link = element('a');
      link.href = url;
      link.download = `${layer.name.replaceAll(/[\\/:*?"<>|]/g, '_')}.geojson`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    function renderLayerOptions() {
      const selected = markerLayer.value || active;
      markerLayer.replaceChildren(...layers.map((layer) => new Option(layer.name, layer.id)));
      if (layers.some(({ id }) => id === selected)) markerLayer.value = selected;
    }

    function renderLayerList() {
      layerList.replaceChildren();
      for (const layer of layers) {
        const entry = element('div', null, 'tak-workbench-layer');
        const label = element('label');
        const visible = element('input');
        visible.type = 'checkbox';
        visible.checked = layer.visible;
        visible.addEventListener('change', async () => {
          const checked = visible.checked;
          if (!await save((all) => all.map((item) => item.id === layer.id
            ? { ...item, visible: checked } : item), checked ? '图层已显示' : '图层已隐藏')) {
            visible.checked = layer.visible;
          }
        });
        label.append(visible, element('span', layer.name), element('small', layer.features.length));
        const tools = element('div', null, 'tak-workbench-bar');
        if (layer.features.length) tools.append(button('导出', () => exportLayer(layer)));
        if (layers.length > 1) tools.append(button('移除', () => {
          if (window.confirm(`移除本浏览器中的“${layer.name}”图层？`)) {
            save((all) => all.filter((item) => item.id !== layer.id || all.length === 1), '图层已移除');
          }
        }));
        entry.append(label, tools);
        layerList.append(entry);
      }
    }

    function render() {
      renderLayerList();
      renderLayerOptions();
      states.clearLayers();
      layers.forEach((layer, index) => {
        if (!layer.visible) return;
        const color = colors[index % colors.length];
        leaflet.geoJSON(layer.features, {
          style: { color, weight: 3, fillOpacity: .15 },
          pointToLayer: (_, latlng) => leaflet.circleMarker(latlng, {
            radius: 8, weight: 2, color: 'white', fillColor: color, fillOpacity: 1, bubblingMouseEvents: false,
          }),
          onEachFeature: ({ properties }, item) => {
            const contents = element('div', null, 'tak-workbench-popup');
            contents.append(element('strong', properties.name || layer.name));
            if (properties.markerId) contents.append(button('移除标记', () => {
              save((all) => all.map((entry) => entry.id !== layer.id ? entry : {
                ...entry, features: entry.features.filter((feature) => feature.properties.markerId !== properties.markerId),
              }), '标记已移除');
            }));
            item.bindPopup(contents);
          },
        }).addTo(states);
      });
    }

    function keydown(event) {
      if (event.key !== 'Escape') return;
      if (placing || draft) cancelMark();
      else if (!panel.hidden) layerToggle.click();
    }

    function refreshStorage(event) {
      if (!key || event.key !== key) return;
      try {
        layers = persisted();
        render();
      } catch {
        say('无法读取保存的图层');
      }
    }

    function destroy() {
      controller.abort();
      cancelMark();
      control.remove();
      states.remove();
      map.off('click', pick);
      map.off('unload', destroy);
      document.removeEventListener('keydown', keydown);
      window.removeEventListener('storage', refreshStorage);
      if (mounted === state) mounted = undefined;
    }

    box.append(bar, placementTools, panel, markerForm, status);
    leaflet.DomEvent.disableClickPropagation(box);
    leaflet.DomEvent.disableScrollPropagation(box);
    control.onAdd = () => box;
    control.addTo(map);
    mounted = state;
    map.on('click', pick);
    map.on('unload', destroy);
    document.addEventListener('keydown', keydown);
    window.addEventListener('storage', refreshStorage);
    try {
      const response = await fetch('/api/me', { cache: 'no-store',
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
      if (!response.ok) throw new Error();
      const { username } = await response.json();
      if (mounted !== state) return;
      if (!title(username)) throw new Error();
      key = `golf-tak-map-workbench-v1:${encodeURIComponent(username)}`;
      layers = persisted();
      render();
      [mark, layerToggle].forEach((item) => { item.disabled = false; });
    } catch {
      if (mounted === state) say('无法打开当前账号的图层');
    }
  }

  window.addEventListener('golf-tak-map-mounted', ({ detail }) => attach(detail.map, detail.leaflet));
  window.addEventListener('golf-tak-map-unmounted', ({ detail }) => {
    if (mounted?.map === detail.map) mounted.destroy();
  });
})();
