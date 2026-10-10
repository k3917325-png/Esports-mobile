# Esports Reward — Cloud API

Zero-dependency Node (18+) server. User progress (coins, RP, streak, bonus state) + tournament scores cloud par save hote hain.

## Endpoints
| Method | Path | Kaam |
|---|---|---|
| POST | `/api/register` | Naya phone → account + device token. Existing phone → 409 (OTP restore chahiye) |
| POST | `/api/otp/send`, `/api/otp/verify` | Existing account ko naye device par SMS OTP se restore (purane token invalid ho jate hain) |
| GET/PUT | `/api/progress` | Progress read / write (Bearer token, revision check) |
| POST | `/api/score` | Tournament score — server par cap hota hai (elapsed time × max rate), `boost:2` sirf 2× ad ke liye |
| GET | `/api/leaderboard/:roundId` | Round ke Top 50 |
| GET | `/api/health` | Health check |

## Deploy
Koi bhi Node host (Render, Railway, Fly.io, VPS...) ya `Dockerfile`. Environment variables `.env.example` mein hain.
- `DATA_FILE` ko **persistent disk/volume** par rakho, warna restart par data jata hai.
- Production mein `DEV_OTP=0` aur `SMS_WEBHOOK_URL` / `SMS_WEBHOOK_AUTH` set karo (SMS provider key sirf yahin, app mein kabhi nahi).
- HTTPS zaroori hai (host ye deta hai).

Deploy ke baad URL app mein lagao: Admin Panel → ☁️ Cloud Backend, ya `CLOUD_API_DEFAULT` constant (APK mein sab users ke liye wahi chalega).

## Abhi kya guarantee hai, kya nahi
- ✅ Progress safe (logout/phone badalne par OTP se restore), scores server-side capped, coin jump > 2M flagged (`db.flags`).
- ⚠️ Games abhi phone par chalte hain, isliye coins/RP ka *final* authority abhi bhi client hai. Full anti-cheat ke liye next step: server hi RP/coins award kare (tournament payout, daily bonus, promo).
- ⚠️ JSON-file storage chhote scale ke liye hai; users badhne par Postgres par shift karo.
