(() => {
  if (window.parent === window || !document.referrer) return;
  const parentOrigin = new URL(document.referrer).origin;
  if (parentOrigin !== 'http://127.0.0.1:18080') return;

  document.documentElement.classList.add('ops-map-embedded');
  let previous;

  function report() {
    if (location.pathname === '/dashboard' && localStorage.getItem('loggedIn') === 'true') {
      location.replace('/map');
      return;
    }
    const status = location.pathname === '/login' ? 'login'
      : document.querySelector('.leaflet-container') ? 'open' : 'loading';
    if (status === previous) return;
    previous = status;
    window.parent.postMessage({ type: 'ops-native-tak-map', status }, parentOrigin);
  }

  new MutationObserver(report).observe(document.body, { childList: true, subtree: true });
  window.addEventListener('storage', (event) => {
    if (event.key === 'token' && event.newValue && location.pathname === '/login') {
      location.replace('/map');
    }
  });
  report();
})();
