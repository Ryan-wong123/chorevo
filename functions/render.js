const fs = require("fs");
const path = require("path");

const TEMPLATE = fs.readFileSync(path.join(__dirname, "dance.html"), "utf8");

const DEFAULT_TITLE = "Check out this dance on Chorevo";
const DEFAULT_DESCRIPTION =
  "Watch this dance and practise it with real-time pose feedback on Chorevo.";
/** Branded 1200×630 preview image used when a dance has no cover thumbnail. */
const DEFAULT_IMAGE = "https://chorevo-dea05.web.app/og-default.png";

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Firestore auto-IDs and similar: no "/" (which would address a subcollection), no odd characters. */
const DANCE_ID = /^[A-Za-z0-9_-]{1,128}$/;

/** Extracts the dance ID from a /dance/<id> path, or null. */
function parseDanceId(urlPath) {
  const match = /^\/dance\/([^/?#]+)\/?$/.exec(urlPath || "");
  if (!match) return null;
  try {
    const id = decodeURIComponent(match[1]).trim();
    return DANCE_ID.test(id) ? id : null;
  } catch (_) {
    return null;
  }
}

/**
 * Media URLs shown on the page and in link previews must be this project's
 * own Storage files for dances, never an arbitrary URL a user wrote into
 * their dance document.
 */
const MEDIA_URL =
  /^https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.(?:firebasestorage\.app|appspot\.com)\/o\/dance(?:Videos|Thumbnails)%2F[^"'<>\s]+$/;

function safeMediaUrl(url) {
  return typeof url === "string" && MEDIA_URL.test(url) ? url : null;
}

/**
 * Renders the share landing page. `dance` is the Firestore danceLibrary
 * document data, or null when the dance is missing/unavailable. Private
 * dances are treated as unavailable so their title and video never leak.
 */
function renderDancePage(dance, url) {
  // Allowlist, as in firestore.rules: only public (or legacy unset) dances show.
  const visibility = dance && (dance.visibility ?? "public");
  const available = dance && dance.enabled !== false && visibility === "public" && dance.title;
  const videoUrl = available ? safeMediaUrl(dance.videoUrl) : null;
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
    videoUrl
      ? `    <meta property="og:video" content="${escapeHtml(videoUrl)}">\n` +
        `    <meta property="og:video:secure_url" content="${escapeHtml(videoUrl)}">\n` +
        `    <meta property="og:video:type" content="video/mp4">\n`
      : "";

  // The dance's cover frame (uploaded with the video) or the branded default.
  const thumbnail = available ? safeMediaUrl(dance.thumbnailUrl) : null;
  const image = thumbnail || DEFAULT_IMAGE;
  const imageTags =
    `    <meta property="og:image" content="${escapeHtml(image)}">\n` +
    `    <meta property="og:image:secure_url" content="${escapeHtml(image)}">\n` +
    `    <meta property="og:image:alt" content="${escapeHtml(title)}">\n` +
    (thumbnail ? "" : `    <meta property="og:image:width" content="1200">\n    <meta property="og:image:height" content="630">\n`) +
    `    <meta name="twitter:image" content="${escapeHtml(image)}">\n`;

  const poster = thumbnail ? ` poster="${escapeHtml(thumbnail)}"` : "";
  const videoPlayer =
    videoUrl
      ? `      <video class="video" src="${escapeHtml(videoUrl)}"${poster} controls autoplay muted loop playsinline></video>\n`
      : "";

  const values = {
    PAGE_TITLE: escapeHtml(available ? `${title} | Chorevo` : "Chorevo"),
    TITLE: escapeHtml(title),
    DESCRIPTION: escapeHtml(description),
    URL: escapeHtml(url),
    VIDEO_TAGS: videoTags,
    IMAGE_TAGS: imageTags,
    VIDEO_PLAYER: videoPlayer,
  };
  return TEMPLATE.replace(/\{\{(\w+)\}\}/g, (whole, key) =>
    key in values ? values[key] : whole
  );
}

module.exports = { escapeHtml, parseDanceId, safeMediaUrl, renderDancePage, DEFAULT_IMAGE };
