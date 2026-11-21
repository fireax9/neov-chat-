import http from 'node:http';

const PORT = 3000;

const html = `<!doctype html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>neov chat — Android App</title>
  <style>
    :root { color-scheme: dark; }
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      background: #000000;
      color: #f7f8fa;
      font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      padding: 24px;
    }
    .card {
      max-width: 560px;
      width: 100%;
      background: #0f1117;
      border: 1px solid rgba(88, 101, 242, 0.35);
      border-radius: 20px;
      padding: 28px;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.65);
    }
    .badge {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 8px;
      background: rgba(88, 101, 242, 0.18);
      color: #8ea1e1;
      font-size: 12px;
      font-weight: 700;
      margin-bottom: 12px;
    }
    h1 { margin: 0 0 8px; font-size: 24px; }
    p { margin: 0 0 14px; color: #b5bac1; line-height: 1.6; }
    code {
      display: block;
      background: #050608;
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 10px;
      padding: 10px 12px;
      direction: ltr;
      text-align: left;
      color: #5eead4;
      font-size: 13px;
    }
  </style>
</head>
<body>
  <main class="card">
    <span class="badge">Android • Kotlin &amp; Jetpack Compose</span>
    <h1>neov chat</h1>
    <p>هذا المشروع هو تطبيق أندرويد أصلي (<code>com.aistudio.neovchat.kqzwmr</code>) وليس تطبيق ويب. يتم تشغيل التطبيق عبر بناء ملف APK على جهاز أندرويد أو المحاكي.</p>
    <code>gradle assembleDebug</code>
  </main>
</body>
</html>`;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`neov chat Android preview info server listening on http://0.0.0.0:${PORT}`);
});
