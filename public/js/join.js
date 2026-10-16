// Group invite landing page (join.html): opens the invite in the app.
// Served as a file (not inline) so the pages can use a strict Content-Security-Policy.
(function () {
  var ANDROID_PACKAGE = 'com.example.chorevo';
  var ALPHABET = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/;

  // /join/<code>  →  <code>
  var match = location.pathname.match(/^\/join\/([^\/?#]+)/);
  var code = match ? decodeURIComponent(match[1]).toUpperCase().replace(/[^A-Z0-9]/g, '') : '';
  var valid = ALPHABET.test(code);
  var params = new URLSearchParams(location.search);
  var isAndroid = /android/i.test(navigator.userAgent);

  var schemeUrl = 'chorevo://join/' + code;
  var fallback = location.origin + location.pathname + '?app=missing';
  var intentUrl = 'intent://join/' + code +
    '#Intent;scheme=chorevo;package=' + ANDROID_PACKAGE +
    ';S.browser_fallback_url=' + encodeURIComponent(fallback) + ';end';

  var openBtn = document.getElementById('open');
  var hint = document.getElementById('hint');
  document.getElementById('code').textContent = valid ? code : '—';
  openBtn.href = isAndroid ? intentUrl : schemeUrl;

  if (!valid) {
    hint.textContent = 'This invite link looks incomplete.';
    openBtn.style.display = 'none';
    document.getElementById('copy').style.display = 'none';
  } else if (params.get('app') === 'missing') {
    hint.textContent = 'Chorevo isn’t installed on this device yet.';
  } else if (isAndroid) {
    location.replace(intentUrl);
  }

  document.getElementById('copy').addEventListener('click', function () {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(code).then(function () { hint.textContent = 'Code copied'; });
    } else {
      hint.textContent = code;
    }
  });
})();
