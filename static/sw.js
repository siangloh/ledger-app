const CACHE_NAME = 'ledger-pwa-v4';
const PRECACHE_ASSETS = [
  '/static/manifest.json',
  '/static/style.css',
  '/static/app.js',
  '/static/offline.html',
  '/static/icons/icon-192.png',
  '/static/icons/icon-512.png',
  '/static/icons/icon-maskable-512.png',
  '/static/icons/apple-touch-icon.png',
  '/static/icons/favicon.svg'
];

// 安装时预缓存基础核心静态资源
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_ASSETS))
      .then(() => self.skipWaiting())
  );
});

// 激活时清理旧版本缓存
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== CACHE_NAME) {
            return caches.delete(cacheName);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// 拦截请求并实施定制化财务缓存策略
self.addEventListener('fetch', (event) => {
  const request = event.request;

  // 1. 只处理 GET 请求；写操作/POST/DELETE 直接放行网络
  if (request.method !== 'GET') {
    return;
  }

  const url = new URL(request.url);

  // 2. 严禁缓存财务数据接口与动态操作路由 (Network-Only)
  const isDynamicRoute = 
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/partial/') ||
    url.pathname.startsWith('/transactions/') ||
    url.pathname.startsWith('/nlp/') ||
    url.pathname.startsWith('/auto-track') ||
    url.pathname.startsWith('/split-bill/') ||
    url.pathname.startsWith('/recurring/') ||
    url.pathname.startsWith('/import/') ||
    url.pathname.startsWith('/categories/') ||
    url.pathname === '/health';

  if (isDynamicRoute) {
    return; // 交由浏览器默认网络层处理，不走 SW 缓存
  }

  // 3. 页面导航请求 (HTML Document)：采用 Network-First 策略
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(async () => {
        // 断网时返回美观的离线兜底页
        const cache = await caches.open(CACHE_NAME);
        const cachedOffline = await cache.match('/static/offline.html');
        return cachedOffline || new Response('Offline', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
      })
    );
    return;
  }

  // 4. 静态资源 (/static/ 路径)：采用 Stale-While-Revalidate 策略
  if (url.pathname.startsWith('/static/')) {
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cachedResponse = await cache.match(request);
        const networkFetch = fetch(request).then((networkResponse) => {
          if (networkResponse && networkResponse.status === 200) {
            cache.put(request, networkResponse.clone());
          }
          return networkResponse;
        }).catch(() => null);

        // 如果本地有缓存先返回缓存，同时后台异步更新；否则等待网络
        return cachedResponse || networkFetch;
      })
    );
    return;
  }
});

// 点击手机原生通知打开/唤起应用
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url && 'focus' in client) {
          return client.focus();
        }
      }
      if (clients.openWindow) {
        return clients.openWindow('/');
      }
    })
  );
});

