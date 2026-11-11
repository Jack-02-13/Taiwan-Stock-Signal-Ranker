const CACHE_NAME = "tw-stock-ranker-v7-theme-switch-colors";
const BASE_URL = new URL("./", self.registration.scope);
const RANKINGS_URL = new URL("data/rankings.json", BASE_URL);
const APP_SHELL = [
  BASE_URL.href,
  new URL("index.html", BASE_URL).href,
  new URL("styles.css", BASE_URL).href,
  new URL("app.js", BASE_URL).href,
  new URL("manifest.webmanifest", BASE_URL).href,
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const requestUrl = new URL(request.url);
  if (requestUrl.origin !== self.location.origin) return;

  if (requestUrl.pathname === RANKINGS_URL.pathname) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(RANKINGS_URL.href, copy));
          }
          return response;
        })
        .catch(async () => (await caches.match(RANKINGS_URL.href)) || Response.error())
    );
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(async () => (await caches.match(request)) || (await caches.match(new URL("index.html", BASE_URL).href)))
    );
    return;
  }

  if (APP_SHELL.includes(request.url)) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => (await caches.match(request)) || Response.error())
    );
    return;
  }

  event.respondWith(caches.match(request).then((cached) => cached || fetch(request)));
});

