# TradeFloat AI 2.0 — Android starter

Package: `com.example.tradefloat`  
Platform: Android 8.0+ (API 26+)  
Architecture: MediaProjection → in-memory frame ring buffer → lightweight local frame sampler → Cloudflare Workers AI vision → floating signal overlay.

## What this starter does
- Requests Android screen-capture consent each session.
- Runs a mediaProjection foreground service.
- Samples the visible screen at a capped rate (about 2 fps) and keeps at most four frames in memory.
- Shows a floating panel with 30 sec, 1 min and STOP.
- Sends up to three recent frames only when a horizon button is pressed.
- Uses Cloudflare Workers AI vision and returns UP / DOWN / WAIT.
- Never places trades or interacts with the trading app.

## Important limitations
- This is a starter implementation, not a validated trading system and not a promise of profitable or 90% accurate signals.
- `ChartFrameAnalyzer` only reports basic image quality and red/green pixel ratios. It does NOT reconstruct OHLC candles or make a trading decision.
- Entry/stop/target values are only returned if legible in the screenshot. The AI is instructed not to invent prices.
- Some apps/windows use Android secure-window protections and may appear black in screen capture. Some apps may prohibit capture in their terms.
- Screen capture is user-consented, visible and stoppable. Android requires a fresh consent flow for each new projection session.
- The sample keeps screenshots in memory only; it does not persist them to disk.
- Multiple platforms means any compatible visible chart, not a platform-specific API integration. The app cannot guarantee identical chart interpretation across platforms/layouts.

## Android build
1. Open this folder in Android Studio (JDK 17 recommended).
2. Let Gradle sync.
3. In `WorkerClient.kt`, replace `WORKER_URL` with your deployed HTTPS Worker URL ending in `/predict`.
4. Build > Build APK(s).
5. Install the APK. Open TradeFloat AI, grant "Display over other apps", then tap Start capture and approve Android's screen-sharing prompt.
6. Switch to a charting app. The overlay should remain visible. Tap 30 sec or 1 min to request a cloud analysis. Tap STOP to end capture.

## Deploy Cloudflare Worker
Prerequisites: Cloudflare account with Workers AI enabled and Wrangler installed/logged in.

1. Open a terminal in `worker/`.
2. Run `npx wrangler deploy`.
3. In Cloudflare dashboard, confirm the Worker has the Workers AI binding named `AI` (wrangler.toml already declares it).
4. The deployed endpoint is `https://<worker-name>.<subdomain>.workers.dev/predict`.
5. Test `GET https://<worker-url>/health`.
6. If Cloudflare asks you to accept Meta's model terms, follow the model's official Cloudflare setup instructions before use.

## Latency and data use
Capture is local and capped around 2 frames/second. Cloud vision is called only on button press, with at most three compressed JPEG frames; the current Worker uses the newest frame for the model call to reduce latency. Network/model latency varies. The Android client uses a 4.5-second connect timeout and 9-second read timeout.

## Next engineering stage
For stronger analysis, integrate a legitimate market-data feed for structured OHLCV candles and compute indicators locally. Screenshot vision should validate chart layout/pattern context, not be treated as an authoritative price feed. Add per-platform chart-region calibration only after testing on each target app. Before real-money use, evaluate on unseen data and a long paper-trading sample.
