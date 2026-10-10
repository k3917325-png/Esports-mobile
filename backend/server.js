// Esports Reward — Cloud API (zero dependencies, Node 18+)
// Secrets (SMS provider key, admin token, etc.) live ONLY in environment variables on this server.
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = parseInt(process.env.PORT || "8080", 10);
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, "data", "db.json");
const DEV_OTP = process.env.DEV_OTP === "1";               // dev only: returns OTP in response instead of SMS
const SMS_WEBHOOK_URL = process.env.SMS_WEBHOOK_URL || ""; // your SMS provider's endpoint (server-side only)
const SMS_WEBHOOK_AUTH = process.env.SMS_WEBHOOK_AUTH || ""; // e.g. "Bearer xxxxx" (never sent to the app)
const MAX_BODY = 200 * 1024;

// Same anti-cheat rates as the app's bank(): max plausible score per second of play, per game
const MAX_RATE = { arctic: 9, hoop: 7, loop: 15, colorbounce: 9, bubble: 9, drive: 15, shooter: 16, knife: 4, blockmatch: 34 };
// Progress fields the app is allowed to sync (admin config / ad config / withdrawals are NOT accepted here).
// Tournament scores are NOT accepted via progress sync — only via /api/score, where they are validated and capped.
const PROGRESS_KEYS = ["coins", "rp", "joined", "playCount", "streakDay", "lastStreakClaim", "lastBonus",
  "lastBonusDayNum", "usedPromos", "lastLbPayout", "wdSeq", "wdCountToday", "wdCountDate"];
const MAX_COIN_JUMP_PER_SYNC = 2000000; // bigger jumps are accepted but flagged for admin review

// ---------- tiny JSON-file store (atomic writes). Swap for Postgres when you outgrow it. ----------
let db = { users: {}, scores: {}, flags: [] };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DATA_FILE, "utf8"))); } catch (e) {}
let dirty = false;
function persist() {
  if (!dirty) return;
  dirty = false;
  fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
  const tmp = DATA_FILE + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(db));
  fs.renameSync(tmp, DATA_FILE);
}
setInterval(persist, 2000).unref();
const touch = () => { dirty = true; };
process.on("SIGTERM", () => { persist(); process.exit(0); });
process.on("SIGINT", () => { persist(); process.exit(0); });

// ---------- helpers ----------
const sha = s => crypto.createHash("sha256").update(String(s)).digest("hex");
const newToken = () => crypto.randomBytes(32).toString("hex");
const normPhone = p => { const s = String(p || "").replace(/[^\d+]/g, ""); return /^\+\d{7,16}$/.test(s) ? s : ""; };
const num = (v, d = 0) => (typeof v === "number" && isFinite(v) ? v : d);

const buckets = new Map(); // ip+route -> {n, t}
function rateLimited(key, max, windowMs) {
  const t = Date.now(), b = buckets.get(key);
  if (!b || t - b.t > windowMs) { buckets.set(key, { n: 1, t }); return false; }
  return ++b.n > max;
}
setInterval(() => { const t = Date.now(); for (const [k, b] of buckets) if (t - b.t > 3600e3) buckets.delete(k); }, 600e3).unref();

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*", // bearer-token API (no cookies), so wildcard is safe
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
    "Cache-Control": "no-store"
  });
  res.end(body);
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on("data", c => { size += c.length; if (size > MAX_BODY) { reject(new Error("too large")); req.destroy(); } else chunks.push(c); });
    req.on("end", () => { try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {}); } catch (e) { reject(new Error("bad json")); } });
    req.on("error", reject);
  });
}
function authUser(req) {
  const m = /^Bearer\s+([a-f0-9]{64})$/.exec(req.headers.authorization || "");
  if (!m) return null;
  const h = sha(m[1]);
  for (const phone in db.users) {
    const u = db.users[phone];
    if (u.tokenHash === h) return { phone, u };
  }
  return null;
}
async function sendSms(phone, text) {
  if (!SMS_WEBHOOK_URL) return false;
  const r = await fetch(SMS_WEBHOOK_URL, {
    method: "POST",
    headers: Object.assign({ "Content-Type": "application/json" }, SMS_WEBHOOK_AUTH ? { Authorization: SMS_WEBHOOK_AUTH } : {}),
    body: JSON.stringify({ to: phone, message: text })
  });
  return r.ok;
}
function sanitizeProgress(p) {
  const out = {};
  if (!p || typeof p !== "object") return out;
  for (const k of PROGRESS_KEYS) if (k in p) out[k] = p[k];
  out.coins = Math.max(0, Math.floor(num(out.coins)));
  out.rp = Math.max(0, Math.floor(num(out.rp)));
  return JSON.parse(JSON.stringify(out)); // drop functions / undefined, enforce plain JSON
}

// ---------- routes ----------
async function handle(req, res) {
  const url = new URL(req.url, "http://x");
  const ip = (req.headers["x-forwarded-for"] || req.socket.remoteAddress || "").split(",")[0].trim();
  if (req.method === "OPTIONS") return send(res, 204, {});
  if (rateLimited(ip + "|all", 240, 60e3)) return send(res, 429, { error: "slow down" });

  if (url.pathname === "/api/health") return send(res, 200, { ok: true, time: Date.now() });

  // First registration of a phone: creates the account and returns the device token.
  if (url.pathname === "/api/register" && req.method === "POST") {
    if (rateLimited(ip + "|reg", 20, 3600e3)) return send(res, 429, { error: "too many signups" });
    const b = await readJson(req), phone = normPhone(b.phone);
    if (!phone) return send(res, 400, { error: "bad phone" });
    if (db.users[phone]) return send(res, 409, { error: "exists", needOtp: true });
    const token = newToken();
    db.users[phone] = { tokenHash: sha(token), name: String(b.name || "").slice(0, 40), cc: String(b.cc || "").slice(0, 6),
      countryId: String(b.countryId || "").slice(0, 3), progress: null, rev: 0, createdAt: Date.now(), updatedAt: Date.now() };
    touch();
    return send(res, 200, { token, rev: 0, progress: null });
  }

  // Existing account on a new device/after logout: prove phone ownership with a real SMS OTP.
  if (url.pathname === "/api/otp/send" && req.method === "POST") {
    const b = await readJson(req), phone = normPhone(b.phone);
    if (!phone || !db.users[phone]) return send(res, 404, { error: "no account" });
    if (rateLimited(ip + "|otp", 10, 3600e3) || rateLimited(phone + "|otp", 5, 3600e3)) return send(res, 429, { error: "too many OTP requests" });
    const code = String(crypto.randomInt(100000, 1000000));
    db.users[phone].otp = { hash: sha(phone + ":" + code), exp: Date.now() + 5 * 60e3, tries: 0 };
    touch();
    let sent = false;
    try { sent = await sendSms(phone, `Esports Reward OTP: ${code} (5 min valid)`); } catch (e) {}
    if (!sent && !DEV_OTP) return send(res, 503, { error: "SMS provider not configured on server" });
    return send(res, 200, DEV_OTP && !sent ? { ok: true, devOtp: code } : { ok: true });
  }
  if (url.pathname === "/api/otp/verify" && req.method === "POST") {
    const b = await readJson(req), phone = normPhone(b.phone), u = db.users[phone];
    if (!u || !u.otp) return send(res, 400, { error: "request OTP first" });
    if (Date.now() > u.otp.exp || u.otp.tries >= 5) { delete u.otp; touch(); return send(res, 400, { error: "OTP expired" }); }
    u.otp.tries++;
    if (sha(phone + ":" + String(b.code || "")) !== u.otp.hash) { touch(); return send(res, 400, { error: "wrong OTP" }); }
    delete u.otp;
    const token = newToken(); u.tokenHash = sha(token); u.updatedAt = Date.now(); touch(); // old device tokens stop working
    return send(res, 200, { token, rev: u.rev, progress: u.progress, bestRounds: u.bestRounds || {} });
  }

  const me = authUser(req);
  if (!me) return send(res, 401, { error: "unauthorized" });
  const { phone, u } = me;

  if (url.pathname === "/api/progress" && req.method === "GET") return send(res, 200, { rev: u.rev, progress: u.progress, bestRounds: u.bestRounds || {} });

  if (url.pathname === "/api/progress" && req.method === "PUT") {
    const b = await readJson(req), p = sanitizeProgress(b.progress), baseRev = parseInt(b.baseRev, 10);
    if (isNaN(baseRev) || baseRev !== u.rev) return send(res, 409, { error: "stale", rev: u.rev, progress: u.progress, bestRounds: u.bestRounds || {} }); // another device wrote newer data
    const prev = u.progress || {};
    if (p.coins - num(prev.coins) > MAX_COIN_JUMP_PER_SYNC) { db.flags.push({ phone, t: Date.now(), kind: "coin-jump", from: num(prev.coins), to: p.coins }); if (db.flags.length > 500) db.flags.shift(); }
    u.progress = p; u.rev++; u.updatedAt = Date.now(); touch();
    return send(res, 200, { rev: u.rev });
  }

  // Tournament score: validated + capped server-side, stored per round so the winning report stays tamper-resistant.
  if (url.pathname === "/api/score" && req.method === "POST") {
    if (rateLimited(phone + "|score", 120, 3600e3)) return send(res, 429, { error: "too many scores" });
    const b = await readJson(req), game = String(b.game || ""), roundId = String(b.roundId || "").slice(0, 40);
    if (!MAX_RATE[game] || !roundId) return send(res, 400, { error: "bad game/round" });
    const elapsed = Math.max(0.5, Math.min(3600, num(b.elapsedSec, 0.5)));
    const boost = num(b.boost, 1) === 2 ? 2 : 1; // "2x" rewarded-ad boost doubles the score
    const cap = (Math.ceil(elapsed * MAX_RATE[game]) + 25) * boost;
    const score = Math.min(Math.max(0, Math.floor(num(b.score))), cap);
    const round = (db.scores[roundId] = db.scores[roundId] || {});
    if (score > (round[phone] || 0)) { round[phone] = score; touch(); }
    const tourId = String(b.tourId || "").slice(0, 12);
    if (tourId) {
      u.bestRounds = u.bestRounds || {};
      const cur = u.bestRounds[tourId];
      if (!cur || cur.roundId !== roundId || cur.score < round[phone]) { u.bestRounds[tourId] = { roundId, score: round[phone] }; touch(); }
    }
    const mine = round[phone] || 0, all = Object.values(round);
    return send(res, 200, { best: mine, rank: all.filter(s => s > mine).length + 1, players: all.length, capped: score < num(b.score) });
  }
  if (url.pathname.startsWith("/api/leaderboard/") && req.method === "GET") {
    const round = db.scores[decodeURIComponent(url.pathname.split("/").pop())] || {};
    const top = Object.entries(round).sort((a, b) => b[1] - a[1]).slice(0, 50)
      .map(([ph, score], i) => ({ rank: i + 1, name: (db.users[ph] && db.users[ph].name) || "Player", score, me: ph === phone }));
    return send(res, 200, { top, players: Object.keys(round).length });
  }
  return send(res, 404, { error: "not found" });
}

http.createServer((req, res) => {
  handle(req, res).catch(e => send(res, e.message === "too large" ? 413 : 400, { error: e.message || "error" }));
}).listen(PORT, () => console.log("Esports API on :" + PORT));
