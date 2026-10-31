// ה-PWA (/app/): בלי שמירת קבצים במטמון - הכול תמיד מהשרת (התרעות חייבות להיות עדכניות).
// התראות בטלפון נשלחות דרך כאן (באנדרואיד אי אפשר בלי זה)
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window' }).then(l => l.length ? l[0].focus() : self.clients.openWindow('/app/')));
});
