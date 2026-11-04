const MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

function extractJson(text) {
  if (typeof text !== "string") throw new Error("Vision model returned no text");
  const cleaned = text.replace(/```json/gi, "```").replace(/```/g, "").trim();
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  if (start < 0 || end <= start) throw new Error("Could not parse model JSON");
  return JSON.parse(cleaned.slice(start, end + 1));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, {
      headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "POST, OPTIONS", "access-control-allow-headers": "content-type" }
    });
    if (url.pathname === "/health" && request.method === "GET") return json({ ok: true, service: "TradeFloat AI", model: MODEL });
    if (url.pathname !== "/predict" || request.method !== "POST") return json({ error: "Use POST /predict or GET /health" }, 404);
    if (!env.AI) return json({ error: "Workers AI binding AI is missing" }, 500);

    try {
      const body = await request.json();
      const horizonSeconds = Number(body.horizonSeconds);
      const frames = Array.isArray(body.frames) ? body.frames : [];
      if (![30, 60].includes(horizonSeconds)) return json({ error: "horizonSeconds must be 30 or 60" }, 400);
      if (!frames.length || frames.length > 3) return json({ error: "Send between 1 and 3 recent frames" }, 400);
      const newest = String(frames[frames.length - 1] || "").replace(/^data:image\/\w+;base64,/, "");
      if (newest.length < 1000 || newest.length > 5_000_000) return json({ error: "Newest frame is missing or too large" }, 400);
      if (!/^[A-Za-z0-9+/]+=*$/.test(newest)) return json({ error: "Frame must be base64 encoded" }, 400);

      const f = body.features || {};
      const prompt = `You are a cautious visual chart-reading assistant. Analyze this screenshot of a financial chart for a ${horizonSeconds}-second directional horizon. Do not invent exact OHLC prices, asset names, candle intervals, volume, indicators, or numeric entry/stop/target prices that are not clearly visible. A screenshot alone may be insufficient. If chart is obstructed, tiny, ambiguous, not a candlestick chart, or evidence conflicts, return WAIT. Do not claim guaranteed accuracy. The local screen sampler reports quality=${String(f.quality || "unknown")}, dimensions=${Number(f.width) || 0}x${Number(f.height) || 0}; its red/green pixel ratios are not reliable market signals and must not determine direction. Look for visible market structure, trend, support/resistance, breakout/retest, candle confirmation, momentum and volatility. Return ONLY valid JSON with keys: direction (UP, DOWN, or WAIT), confidence (integer 0-100; calibrated visual confidence, not win probability), entry (visible price/zone only or "Not reliably readable"), stopLoss (visible/invalidation level only or "Not reliably readable"), target (visible level only or "Not reliably readable"), reason (brief evidence-based explanation), warning (brief risk caveat). Use WAIT and confidence <= 45 if evidence is weak. Never recommend increasing stake or chasing losses.`;

      const dataUrl = `data:image/jpeg;base64,${newest}`;
      const result = await env.AI.run(MODEL, { prompt, image: dataUrl, max_tokens: 220, temperature: 0.1 });
      const raw = result?.response ?? result?.result ?? result?.output_text ?? "";
      let signal;
      try { signal = extractJson(raw); }
      catch { return json({ direction: "WAIT", confidence: 0, entry: "Not available", stopLoss: "Not available", target: "Not available", reason: "AI response could not be safely parsed", warning: "No trade signal issued." }); }
      const direction = ["UP", "DOWN", "WAIT"].includes(String(signal.direction).toUpperCase()) ? String(signal.direction).toUpperCase() : "WAIT";
      return json({
        direction,
        confidence: Math.max(0, Math.min(100, Number(signal.confidence) || 0)),
        entry: String(signal.entry || "Not available").slice(0, 100),
        stopLoss: String(signal.stopLoss || "Not available").slice(0, 100),
        target: String(signal.target || "Not available").slice(0, 100),
        reason: String(signal.reason || "Insufficient visual evidence").slice(0, 300),
        warning: String(signal.warning || "Visual estimate only; verify all prices independently.").slice(0, 220)
      });
    } catch (error) {
      return json({ error: "Prediction failed", detail: String(error?.message || error).slice(0, 300) }, 502);
    }
  }
};
