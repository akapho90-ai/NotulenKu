const test = require("node:test");
const assert = require("node:assert/strict");

const {
  FIREBASE_PROJECT_ID,
  SUMOPOD_BASE_URL,
  validateFirebaseTokenClaims,
  resolveAllowedModels,
  createRateLimiter,
  processSumoPodAi,
} = require("../lib/sumopodAiService");

test("validateFirebaseTokenClaims rejects wrong issuer, audience, or expired token", () => {
  const now = 1_700_000_000;
  const valid = validateFirebaseTokenClaims(
    {
      sub: "uid_123",
      aud: FIREBASE_PROJECT_ID,
      iss: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
      iat: now - 60,
      exp: now + 3600,
      email: "user@contoh.id",
      email_verified: true,
    },
    now
  );
  assert.equal(valid.uid, "uid_123");

  assert.throws(
    () =>
      validateFirebaseTokenClaims(
        {
          sub: "uid_123",
          aud: "other-project",
          iss: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
          iat: now - 60,
          exp: now + 3600,
        },
        now
      ),
    (err) => err.code === "unauthenticated"
  );

  assert.throws(
    () =>
      validateFirebaseTokenClaims(
        {
          sub: "uid_123",
          aud: FIREBASE_PROJECT_ID,
          iss: "https://evil.example.com/project",
          iat: now - 60,
          exp: now + 3600,
        },
        now
      ),
    (err) => err.code === "unauthenticated"
  );

  assert.throws(
    () =>
      validateFirebaseTokenClaims(
        {
          sub: "uid_123",
          aud: FIREBASE_PROJECT_ID,
          iss: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
          iat: now - 4000,
          exp: now - 10,
        },
        now
      ),
    (err) => err.code === "unauthenticated"
  );
});

test("model routing matches required SumoPod allowlist and blocks arbitrary tasks/URLs", async () => {
  assert.deepEqual(resolveAllowedModels("audio_transcription"), ["gemini/gemini-3.5-flash-lite"]);
  assert.deepEqual(resolveAllowedModels("meeting_summary"), ["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]);
  assert.deepEqual(resolveAllowedModels("meeting_qa"), ["gpt-6-luna", "claude-haiku-5-5"]);
  assert.deepEqual(resolveAllowedModels("periodic_summary"), ["claude-haiku-5-5", "gpt-6-luna"]);
  assert.deepEqual(resolveAllowedModels("structured_visual_data"), ["gpt-6-luna", "claude-haiku-5-5"]);

  assert.throws(() => resolveAllowedModels("arbitrary_proxy_task"), (err) => err.code === "invalid-argument");

  await assert.rejects(
    () =>
      processSumoPodAi({
        auth: { uid: "u1" },
        apiKey: "sk-test-secret-key",
        data: { task: "meeting_summary", targetUrl: "https://evil.example.com", userPrompt: "halo" },
      }),
    (err) => err.code === "invalid-argument"
  );
});

test("meeting_summary falls back from glm-5.3-flash to claude-haiku-5-5 then gpt-6-luna on failure/invalid JSON", async () => {
  const seenModels = [];
  const seenUrls = [];

  const mockFetch = async (url, init) => {
    seenUrls.push(url);
    const body = JSON.parse(init.body);
    seenModels.push(body.model);

    if (body.model === "glm-5.3-flash") {
      return { ok: false, status: 503, text: async () => "upstream error" };
    }
    if (body.model === "claude-haiku-5-5") {
      return {
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            choices: [{ message: { content: "BUKAN JSON VALID" } }],
            usage: { prompt_tokens: 10, completion_tokens: 5 },
          }),
      };
    }
    return {
      ok: true,
      status: 200,
      text: async () =>
        JSON.stringify({
          choices: [
            {
              message: {
                content: JSON.stringify({
                  ringkasan: "Ringkasan berhasil lewat cadangan.",
                  poin_utama: ["Poin 1"],
                  keputusan: [],
                  risiko: [],
                  angka_kunci: [],
                  action_items: [],
                }),
              },
            },
          ],
          usage: { prompt_tokens: 50, completion_tokens: 30 },
        }),
    };
  };

  const res = await processSumoPodAi({
    auth: { uid: "u1" },
    apiKey: "sk-test-secret-key",
    fetchImpl: mockFetch,
    data: {
      task: "meeting_summary",
      systemPrompt: "Ringkas rapat",
      userPrompt: "Transkrip rapat singkat.",
      jsonMode: true,
    },
  });

  assert.deepEqual(seenModels, ["glm-5.3-flash", "claude-haiku-5-5", "gpt-6-luna"]);
  assert.ok(seenUrls.every((u) => u === `${SUMOPOD_BASE_URL}/chat/completions`));
  assert.equal(res.modelId, "gpt-6-luna");
  assert.equal(res.usedFallback, true);
});

test("audio_transcription sends structured input_audio, never falls back to text models, and never fabricates transcript", async () => {
  const seenBodies = [];
  const secret = "sk-super-secret-sumopod-value";

  const failingAudioFetch = async (_url, init) => {
    const parsed = JSON.parse(init.body);
    seenBodies.push(parsed);
    return {
      ok: false,
      status: 400,
      text: async () => `Unsupported content type input_audio for key ${secret}`,
    };
  };

  await assert.rejects(
    () =>
      processSumoPodAi({
        auth: { uid: "u1" },
        apiKey: secret,
        fetchImpl: failingAudioFetch,
        data: {
          task: "audio_transcription",
          audioBase64: Buffer.from("fake-m4a-bytes").toString("base64"),
          mimeType: "audio/mp4",
          preferredLanguage: "id",
        },
      }),
    (err) => {
      assert.equal(err.details.code, "AUDIO_INPUT_UNSUPPORTED");
      assert.ok(!err.message.includes(secret), "Secret must never leak in error messages");
      return true;
    }
  );

  // Must only call gemini/gemini-3.5-flash-lite once (no text-model fallback!)
  assert.equal(seenBodies.length, 1);
  assert.equal(seenBodies[0].model, "gemini/gemini-3.5-flash-lite");
  const userContent = seenBodies[0].messages[1].content;
  assert.ok(Array.isArray(userContent));
  assert.equal(userContent[1].type, "input_audio");
  assert.equal(userContent[1].input_audio.format, "m4a");
});

test("rate limiter blocks excessive requests per user", async () => {
  const limiter = createRateLimiter(2, 60_000);
  const okFetch = async () => ({
    ok: true,
    status: 200,
    text: async () =>
      JSON.stringify({
        choices: [{ message: { content: "{\"jawaban\":\"ok\",\"sumber\":[]}" } }],
      }),
  });

  await processSumoPodAi({
    auth: { uid: "u_rate" },
    apiKey: "sk-test-secret",
    fetchImpl: okFetch,
    rateLimiter: limiter,
    data: { task: "meeting_qa", userPrompt: "Pertanyaan 1", jsonMode: true },
  });
  await processSumoPodAi({
    auth: { uid: "u_rate" },
    apiKey: "sk-test-secret",
    fetchImpl: okFetch,
    rateLimiter: limiter,
    data: { task: "meeting_qa", userPrompt: "Pertanyaan 2", jsonMode: true },
  });
  await assert.rejects(
    () =>
      processSumoPodAi({
        auth: { uid: "u_rate" },
        apiKey: "sk-test-secret",
        fetchImpl: okFetch,
        rateLimiter: limiter,
        data: { task: "meeting_qa", userPrompt: "Pertanyaan 3", jsonMode: true },
      }),
    (err) => err.code === "resource-exhausted"
  );
});
