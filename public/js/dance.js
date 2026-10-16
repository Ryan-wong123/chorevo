// Share-link landing page (functions/dance.html): opens the dance in the app.
// Served as a file (not inline) so the pages can use a strict Content-Security-Policy.
(function () {
  var ANDROID_PACKAGE = 'com.example.chorevo';

  // /dance/<id>  →  <id>
  var match = location.pathname.match(/^\/dance\/([^\/?#]+)/);
  var raw = match ? match[1] : '';
  // Same format as the function's parseDanceId (no "/" or odd characters).
  var danceId = /^[A-Za-z0-9_-]{1,128}$/.test(raw) ? raw : '';
  var params = new URLSearchParams(location.search);
  var isAndroid = /android/i.test(navigator.userAgent);

  var schemeUrl = 'chorevo://dance/' + danceId;
  var fallback = location.origin + location.pathname + '?app=missing';
  var intentUrl = 'intent://dance/' + danceId +
    '#Intent;scheme=chorevo;package=' + ANDROID_PACKAGE +
    ';S.browser_fallback_url=' + encodeURIComponent(fallback) + ';end';

  var openBtn = document.getElementById('open');
  var hint = document.getElementById('hint');
  openBtn.href = isAndroid ? intentUrl : schemeUrl;

  if (!danceId) {
    hint.textContent = 'This link looks incomplete.';
    openBtn.style.display = 'none';
  } else if (params.get('app') === 'missing') {
    hint.textContent = 'Chorevo isn’t installed on this device yet.';
  } else if (isAndroid) {
    // Try to jump straight into the app, like TikTok/Instagram links do.
    location.replace(intentUrl);
  }

  document.getElementById('copy').addEventListener('click', function () {
    var link = location.origin + location.pathname;
    if (navigator.clipboard) {
      navigator.clipboard.writeText(link).then(function () { hint.textContent = 'Link copied'; });
    } else {
      hint.textContent = link;
    }
  });
})();
