const fs = require("fs");
const path = require("path");

const TEMPLATE = fs.readFileSync(path.join(__dirname, "dance.html"), "utf8");

const DEFAULT_TITLE = "Check out this dance on Chorevo";
const DEFAULT_DESCRIPTION =
  "Watch this dance and practise it with real-time pose feedback on Chorevo.";

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Extracts the dance ID from a /dance/<id> path, or null. */
function parseDanceId(urlPath) {
  const match = /^\/dance\/([^/?#]+)\/?$/.exec(urlPath || "");
  if (!match) return null;
  try {
    const id = decodeURIComponent(match[1]).trim();
    return id && id.length <= 200 ? id : null;
  } catch (_) {
    return null;
  }
}

/**
 * Renders the share landing page. `dance` is the Firestore danceLibrary
 * document data, or null when the dance is missing/unavailable.
 */
function renderDancePage(dance, url) {
  const available = dance && dance.enabled !== false && dance.title;
  const title = available ? String(dance.title) : DEFAULT_TITLE;

  let description = DEFAULT_DESCRIPTION;
  if (available) {
    const by = dance.educatorName ? `by ${dance.educatorName}` : "";
    const about = (dance.description || "").trim();
    description = [by, about || "Practise it with real-time pose feedback on Chorevo."]
      .filter(Boolean)
      .join(" · ");
  }

  const videoTags =
    available && dance.videoUrl
      ? `    <meta property="og:video" content="${escapeHtml(dance.videoUrl)}">\n` +
        `    <meta property="og:video:secure_url" content="${escapeHtml(dance.videoUrl)}">\n` +
        `    <meta property="og:video:type" content="video/mp4">\n`
      : "";

  const values = {
    PAGE_TITLE: escapeHtml(available ? `${title} | Chorevo` : "Chorevo"),
    TITLE: escapeHtml(title),
    DESCRIPTION: escapeHtml(description),
    URL: escapeHtml(url),
    VIDEO_TAGS: videoTags,
  };
  return TEMPLATE.replace(/\{\{(\w+)\}\}/g, (whole, key) =>
    key in values ? values[key] : whole
  );
}

module.exports = { escapeHtml, parseDanceId, renderDancePage };
