const { ServiceError } = require("./supabaseClient");

const FIREBASE_PROJECT_ID = "gen-lang-client-0830960267";
const SUMOPOD_BASE_URL = "https://ai.sumopod.com/v1";

const MAX_TEXT_CHARS = 150000;
const MAX_SYSTEM_CHARS = 8000;
const MAX_AUDIO_BYTES = 10 * 1024 * 1024; // 10 MB per audio segment
const RATE_WINDOW_MS = 10 * 60 * 1000;
const RATE_LIMIT_MAX_REQUESTS = 90;

const TASK_MODEL_CHAINS = Object.freeze({
  audio_transcription: Object.freeze(["gemini/gemini-3.5-flash-lite"]),
  meeting_summary: Object.freeze(["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]),
  action_item_extraction: Object.freeze(["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]),
  meeting_analysis: Object.freeze(["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]),
  transcript_correction: Object.freeze(["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]),
  meeting_qa: Object.freeze(["gpt-6-luna", "claude-haiku-5-5"]),
  periodic_summary: Object.freeze(["claude-haiku-5-5", "gpt-6-luna"]),
  structured_visual_data: Object.freeze(["gpt-6-luna", "claude-haiku-5-5"]),
  playground: Object.freeze(["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]),
});

const ALLOWED_AUDIO_MIME_TO_FORMAT = Object.freeze({
  "audio/mp4": "m4a",
  "audio/m4a": "m4a",
  "audio/x-m4a": "m4a",
  "audio/aac": "aac",
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/wav": "wav",
  "audio/x-wav": "wav",
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/flac": "flac",
});

function validateFirebaseTokenClaims(claims, nowSec = Math.floor(Date.now() / 1000), projectId = FIREBASE_PROJECT_ID) {
  if (!claims || typeof claims !== "object") {
    throw new ServiceError("unauthenticated", "Token Firebase tidak valid.");
  }
  const sub = typeof claims.sub === "string" ? claims.sub.trim() : "";
  if (!sub || sub.length > 128) {
    throw new ServiceError("unauthenticated", "Subject token Firebase tidak valid.");
  }
  if (claims.aud !== projectId) {
    throw new ServiceError("unauthenticated", "Audience token Firebase tidak cocok.");
  }
  if (claims.iss !== `https://securetoken.google.com/${projectId}`) {
    throw new ServiceError("unauthenticated", "Issuer token Firebase tidak cocok.");
  }
  if (!Number.isFinite(Number(claims.exp)) || Number(claims.exp) <= nowSec) {
    throw new ServiceError("unauthenticated", "Token Firebase telah kedaluwarsa.");
  }
  if (!Number.isFinite(Number(claims.iat)) || Number(claims.iat) > nowSec + 300) {
    throw new ServiceError("unauthenticated", "Waktu penerbitan token Firebase tidak valid.");
  }
  return {
    uid: sub,
    email: typeof claims.email === "string" ? claims.email : null,
    emailVerified: Boolean(claims.email_verified),
  };
}

function resolveAllowedModels(task, requestedModel) {
  const chain = TASK_MODEL_CHAINS[task];
  if (!chain || chain.length === 0) {
    throw new ServiceError("invalid-argument", "Jenis tugas AI tidak diizinkan oleh server.");
  }
  if (task === "audio_transcription") {
    // Strictly lock transcription to gemini/gemini-3.5-flash-lite with NO text-model fallback
    return [...chain];
  }
  if (requestedModel && chain.includes(requestedModel)) {
    return [requestedModel, ...chain.filter((m) => m !== requestedModel)];
  }
  return [...chain];
}

function estimateBase64Bytes(b64) {
  const clean = String(b64 || "").replace(/\s+/g, "");
  const padding = clean.endsWith("==") ? 2 : clean.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((clean.length * 3) / 4) - padding);
}

function extractJsonObject(raw) {
  const trimmed = String(raw || "").trim();
  const fenced = trimmed
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();
  const start = fenced.indexOf("{");
  const end = fenced.lastIndexOf("}");
  if (start >= 0 && end > start) {
    return fenced.slice(start, end + 1);
  }
  return fenced;
}

function sanitizeSecrets(text, secrets = []) {
  let out = String(text || "");
  for (const secret of secrets) {
    if (secret && secret.length >= 6) {
      out = out.split(secret).join("[REDACTED]");
    }
  }
  return out;
}

function createRateLimiter(maxRequests = RATE_LIMIT_MAX_REQUESTS, windowMs = RATE_WINDOW_MS) {
  const buckets = new Map();
  return function check(uid, nowMs = Date.now()) {
    const start = nowMs - windowMs;
    const recent = (buckets.get(uid) || []).filter((ts) => ts >= start);
    if (recent.length >= maxRequests) {
      throw new ServiceError(
        "resource-exhausted",
        "Batas penggunaan AI sementara tercapai. Tunggu beberapa menit lalu coba lagi."
      );
    }
    recent.push(nowMs);
    buckets.set(uid, recent);
  };
}

function extractChatContent(respJson) {
  const choices = Array.isArray(respJson && respJson.choices) ? respJson.choices : [];
  const msg = choices[0] && choices[0].message;
  if (!msg) return "";
  if (typeof msg.content === "string") return msg.content.trim();
  if (Array.isArray(msg.content)) {
    return msg.content
      .map((part) => (typeof part === "string" ? part : part && typeof part.text === "string" ? part.text : ""))
      .join("\n")
      .trim();
  }
  return "";
}

async function processSumoPodAi({
  auth,
  data,
  apiKey,
  fetchImpl = fetch,
  rateLimiter = null,
}) {
  if (!auth || !auth.uid) {
    throw new ServiceError("unauthenticated", "Sesi Firebase diperlukan.");
  }
  if (rateLimiter) {
    rateLimiter(auth.uid);
  }
  if (!apiKey || typeof apiKey !== "string" || !apiKey.trim()) {
    throw new ServiceError(
      "failed-precondition",
      "Layanan AI belum dikonfigurasi pada server (secret SUMOPOD_KEY belum tersedia).",
      { code: "SUMOPOD_KEY_MISSING" }
    );
  }

  const payload = data || {};
  // Reject any attempt to treat this endpoint as an open proxy
  if (payload.targetUrl || payload.baseUrl || payload.customUrl) {
    throw new ServiceError("invalid-argument", "URL tujuan tidak dapat ditentukan oleh klien.");
  }

  const task = typeof payload.task === "string" ? payload.task.trim() : "";
  const models = resolveAllowedModels(task, payload.requestedModel);
  const systemPrompt = typeof payload.systemPrompt === "string" ? payload.systemPrompt.slice(0, MAX_SYSTEM_CHARS) : "";
  const temperature = Math.min(Math.max(Number(payload.temperature ?? 0.2), 0), 1.5);
  const maxTokens = Math.min(Math.max(Math.round(Number(payload.maxTokens ?? 4096)), 256), 16384);
  const jsonMode = Boolean(payload.jsonMode);

  async function invokeModel(model, messages, forceJson) {
    const start = Date.now();
    const body = {
      model,
      messages,
      temperature,
      max_tokens: maxTokens,
      ...(forceJson ? { response_format: { type: "json_object" } } : {}),
    };
    const resp = await fetchImpl(`${SUMOPOD_BASE_URL}/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    const rawText = await resp.text();
    if (!resp.ok) {
      const safe = sanitizeSecrets(rawText.slice(0, 300), [apiKey]).toLowerCase();
      if (resp.status === 429) {
        throw new ServiceError("resource-exhausted", "Kuota sementara model AI penuh.");
      }
      const audioRejected =
        safe.includes("input_audio") ||
        safe.includes("audio") ||
        safe.includes("multimodal") ||
        safe.includes("unsupported");
      if (audioRejected && (resp.status === 400 || resp.status === 404 || resp.status === 415 || resp.status === 422 || resp.status === 501)) {
        throw new ServiceError(
          "failed-precondition",
          "Endpoint atau model SumoPod menolak format input audio ini.",
          { code: "AUDIO_INPUT_UNSUPPORTED", upstreamStatus: resp.status }
        );
      }
      throw new ServiceError("unavailable", `Layanan AI mengembalikan status HTTP ${resp.status}.`, {
        upstreamStatus: resp.status,
      });
    }
    let parsed;
    try {
      parsed = JSON.parse(rawText);
    } catch {
      throw new ServiceError("unavailable", "Respons layanan AI bukan JSON yang valid.");
    }
    const text = extractChatContent(parsed);
    if (!text) {
      throw new ServiceError("unavailable", "Layanan AI mengembalikan respons kosong.");
    }
    const usage = parsed.usage || {};
    return {
      text,
      inputTokens: Number(usage.prompt_tokens || 0),
      outputTokens: Number(usage.completion_tokens || 0),
      latencyMs: Date.now() - start,
    };
  }

  if (task === "audio_transcription") {
    const audioBase64 = typeof payload.audioBase64 === "string" ? payload.audioBase64.replace(/\s+/g, "") : "";
    const mimeType = typeof payload.mimeType === "string" ? payload.mimeType.trim().toLowerCase() : "audio/mp4";
    const format = ALLOWED_AUDIO_MIME_TO_FORMAT[mimeType];
    if (!format) {
      throw new ServiceError("invalid-argument", `Format audio tidak didukung (${mimeType}).`);
    }
    if (!audioBase64) {
      throw new ServiceError("invalid-argument", "Data audio kosong.");
    }
    const bytes = estimateBase64Bytes(audioBase64);
    if (bytes <= 0 || bytes > MAX_AUDIO_BYTES) {
      throw new ServiceError("invalid-argument", "Ukuran segmen audio melebihi batas maksimum 10 MB.");
    }
    const preferredLanguage = typeof payload.preferredLanguage === "string" && payload.preferredLanguage.trim()
      ? payload.preferredLanguage.trim().slice(0, 12)
      : "id";
    const messages = [
      {
        role: "system",
        content:
          systemPrompt ||
          `Transkripsikan rekaman audio rapat secara akurat (bahasa: ${preferredLanguage}) dalam JSON {"bahasa":"${preferredLanguage}","segmen":[{"pembicara":"Pembicara 1","mulaiDetik":0,"selesaiDetik":10,"teks":"..."}]}.`,
      },
      {
        role: "user",
        content: [
          { type: "text", text: "Transkripsikan audio terlampir menjadi JSON terstruktur." },
          { type: "input_audio", input_audio: { data: audioBase64, format } },
        ],
      },
    ];

    const model = models[0];
    const res = await invokeModel(model, messages, true);
    let parsed;
    try {
      parsed = JSON.parse(extractJsonObject(res.text));
    } catch {
      throw new ServiceError(
        "unavailable",
        "Model transkripsi tidak mengembalikan JSON transkrip yang valid.",
        { code: "AUDIO_TRANSCRIPTION_INVALID_OUTPUT" }
      );
    }
    if (!Array.isArray(parsed.segmen)) {
      throw new ServiceError(
        "unavailable",
        "Model transkripsi tidak mengembalikan daftar segmen transkrip yang valid.",
        { code: "AUDIO_TRANSCRIPTION_INVALID_OUTPUT" }
      );
    }
    return {
      text: res.text,
      modelId: model,
      providerLabel: "SumoPod AI",
      usedFallback: false,
      inputTokens: res.inputTokens,
      outputTokens: res.outputTokens,
      latencyMs: res.latencyMs,
    };
  }

  const userPrompt = typeof payload.userPrompt === "string" ? payload.userPrompt : "";
  if (!userPrompt.trim()) {
    throw new ServiceError("invalid-argument", "Input teks untuk pemrosesan AI tidak boleh kosong.");
  }
  if (userPrompt.length > MAX_TEXT_CHARS) {
    throw new ServiceError("invalid-argument", "Ukuran input melebihi batas maksimum.");
  }

  const messages = [];
  if (systemPrompt.trim()) messages.push({ role: "system", content: systemPrompt.trim() });
  messages.push({ role: "user", content: userPrompt });

  let lastError = null;
  for (let i = 0; i < models.length; i++) {
    const model = models[i];
    try {
      const res = await invokeModel(model, messages, jsonMode);
      if (jsonMode) {
        try {
          JSON.parse(extractJsonObject(res.text));
        } catch {
          throw new ServiceError("unavailable", "Respons JSON model tidak valid.");
        }
      }
      return {
        text: res.text,
        modelId: model,
        providerLabel: "SumoPod AI",
        usedFallback: i > 0,
        inputTokens: res.inputTokens,
        outputTokens: res.outputTokens,
        latencyMs: res.latencyMs,
      };
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError || new ServiceError("unavailable", "Semua model AI gagal merespons.");
}

module.exports = {
  FIREBASE_PROJECT_ID,
  SUMOPOD_BASE_URL,
  TASK_MODEL_CHAINS,
  ALLOWED_AUDIO_MIME_TO_FORMAT,
  MAX_AUDIO_BYTES,
  MAX_TEXT_CHARS,
  validateFirebaseTokenClaims,
  resolveAllowedModels,
  estimateBase64Bytes,
  extractJsonObject,
  sanitizeSecrets,
  createRateLimiter,
  processSumoPodAi,
};
