const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { parseDanceId, renderDancePage } = require("./render");

initializeApp();

const PUBLIC_HOST = "chorevo-dea05.web.app";

/**
 * Serves https://chorevo-dea05.web.app/dance/<id> (via the Hosting rewrite in
 * firebase.json). Renders the dance's own title/description into the Open
 * Graph tags so WhatsApp, Instagram, Facebook, Telegram, X, etc. show it in
 * their link previews; the page itself then opens the app.
 */
exports.danceShare = onRequest({ region: "us-central1", maxInstances: 10 }, async (req, res) => {
  const danceId = parseDanceId(req.path);
  const url = `https://${PUBLIC_HOST}${req.path}`;

  let dance = null;
  if (danceId) {
    try {
      const snap = await getFirestore().collection("danceLibrary").doc(danceId).get();
      if (snap.exists) dance = snap.data();
    } catch (err) {
      logger.error("Failed to load dance for share page", { danceId, errorCode: err.code, errorMessage: String(err && err.message) });
    }
  }

  res.set("Cache-Control", "public, max-age=300, s-maxage=600");
  res.status(200).send(renderDancePage(dance, url));
});
