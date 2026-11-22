const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_owner_1";
const BOB_UID = "bob_member_2";
const CHARLIE_UID = "charlie_other_tenant_3";
const TENANT_A = "tenant_alpha";
const TENANT_B = "tenant_beta";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedTenants() {
  const now = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("tenants").doc(TENANT_A).set({
      name: "PT Alpha Digital",
      slug: "pt-alpha-digital",
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      aiMode: "platform",
      watermarkEnabled: true,
      maxFileMb: 200,
      retentionDays: 90,
      monthlyBudgetUsd: 50,
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("tenants").doc(TENANT_B).set({
      name: "PT Beta Karya",
      slug: "pt-beta-karya",
      ownerId: CHARLIE_UID,
      memberUids: [CHARLIE_UID],
      adminUids: [CHARLIE_UID],
      aiMode: "platform",
      watermarkEnabled: true,
      maxFileMb: 200,
      retentionDays: 90,
      monthlyBudgetUsd: 50,
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
  });
}

test("Unauthenticated users are denied on all collections", async () => {
  await seedTenants();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("tenants").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("tenants").doc(TENANT_A).collection("meetings").get()
  );
});

test("User profile strictly isolated and rejects ghost fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      activeTenantId: TENANT_A,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Cross-user PII read rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  // Ghost field rejected
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      email: "bob@example.com",
      platformAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Tenant isolation: Tenant B member cannot list or read Tenant A meetings", async () => {
  await seedTenants();
  const now = new Date(Date.now() - 2000);

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Bob creates a meeting in Tenant A
  await assertSucceeds(
    bobDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .doc("mtg_bob_1")
      .set({
        tenantId: TENANT_A,
        userId: BOB_UID,
        ownerName: "Bob",
        judul: "Rapat Produk Q1",
        status: "done",
        durasiDetik: 120,
        ukuranBytes: 204800,
        consentRecordedAt: now,
        visibleToUids: [BOB_UID, ALICE_UID],
        sharedWithAll: false,
        bahasa: "id",
        transcriptText: "Pembicara 1: Halo tim.",
        segments: [{ pembicara: "Pembicara 1", mulaiDetik: 0, selesaiDetik: 5, teks: "Halo tim." }],
        ringkasan: "Diskusi awal produk.",
        poinUtama: ["Peluncuran Q1"],
        keputusan: ["Setuju rilis Maret"],
        risiko: [],
        angkaKunci: [{ label: "Target", nilai: "1000 user" }],
        actionItems: [],
        createdAt: now,
        updatedAt: now,
      })
  );

  // Scoped query for Bob & Alice succeeds
  await assertSucceeds(
    bobDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .where("visibleToUids", "array-contains", BOB_UID)
      .get()
  );
  await assertSucceeds(
    aliceDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .where("visibleToUids", "array-contains", ALICE_UID)
      .get()
  );

  // Charlie (Tenant B) cannot read or query Tenant A meetings
  await assertFails(
    charlieDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .doc("mtg_bob_1")
      .get()
  );
  await assertFails(
    charlieDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .get()
  );
});

test("Member cannot modify tenant settings or write taskConfigs; Owner/Admin can", async () => {
  await seedTenants();
  const now = new Date(Date.now() - 1000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertFails(
    bobDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("taskConfigs")
      .doc("meeting_summary")
      .set({
        tenantId: TENANT_A,
        task: "meeting_summary",
        providerLabel: "SumoPod AI",
        modelId: "glm-5.3-flash",
        temperature: 0.2,
        maxOutputTokens: 4096,
        enabled: true,
        systemPromptOverride: "",
        memberUids: [ALICE_UID, BOB_UID],
        createdAt: now,
        updatedAt: now,
      })
  );

  for (const [task, modelId] of [
    ["meeting_summary", "glm-5.3-flash"],
    ["periodic_summary", "claude-haiku-5-5"],
  ]) {
    await assertSucceeds(
      aliceDb
        .collection("tenants")
        .doc(TENANT_A)
        .collection("taskConfigs")
        .doc(task)
        .set({
          tenantId: TENANT_A,
          task,
          providerLabel: "SumoPod AI",
          modelId,
          temperature: 0.2,
          maxOutputTokens: 4096,
          enabled: true,
          systemPromptOverride: "",
          memberUids: [ALICE_UID, BOB_UID],
          createdAt: now,
          updatedAt: now,
        })
    );
  }
});

test("Rejects @DocumentId 'id' inside meeting write payload and future timestamps", async () => {
  await seedTenants();
  const future = new Date(Date.now() + 600000);
  const now = new Date(Date.now() - 1000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  await assertFails(
    aliceDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .doc("mtg_bad_id")
      .set({
        id: "mtg_bad_id",
        tenantId: TENANT_A,
        userId: ALICE_UID,
        ownerName: "Alice",
        judul: "Bad Payload",
        status: "done",
        durasiDetik: 10,
        ukuranBytes: 1000,
        consentRecordedAt: now,
        visibleToUids: [ALICE_UID],
        sharedWithAll: false,
        bahasa: "id",
        transcriptText: "",
        segments: [],
        ringkasan: "",
        poinUtama: [],
        keputusan: [],
        risiko: [],
        angkaKunci: [],
        actionItems: [],
        createdAt: now,
        updatedAt: now,
      })
  );

  await assertFails(
    aliceDb
      .collection("tenants")
      .doc(TENANT_A)
      .collection("meetings")
      .doc("mtg_future")
      .set({
        tenantId: TENANT_A,
        userId: ALICE_UID,
        ownerName: "Alice",
        judul: "Future Time",
        status: "done",
        durasiDetik: 10,
        ukuranBytes: 1000,
        consentRecordedAt: now,
        visibleToUids: [ALICE_UID],
        sharedWithAll: false,
        bahasa: "id",
        transcriptText: "",
        segments: [],
        ringkasan: "",
        poinUtama: [],
        keputusan: [],
        risiko: [],
        angkaKunci: [],
        actionItems: [],
        createdAt: future,
        updatedAt: future,
      })
  );
});
