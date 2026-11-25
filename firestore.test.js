const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_windsor_15r";
const BOB_UID = "bob_other_user";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
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

function validVideoPayload(uid, now) {
  return {
    userId: uid,
    uriString: "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fdemo.mkv",
    displayName: "MG Windsor EV Roadtrip 4K.mkv",
    durationMs: 5400000,
    lastPositionMs: 125000,
    audioTrackIndex: 0,
    subtitleTrackIndex: -1,
    playbackSpeed: 1.0,
    aspectMode: "FIT",
    cachedOffline: true,
    mimeType: "video/x-matroska",
    resolutionLabel: "2160p HEVC",
    audioDelayMs: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validPresetPayload(uid, now) {
  return {
    userId: uid,
    name: "Windsor Cabin",
    bandLevelsMb: [200, 100, 0, 150, 250],
    bassBoostStrength: 450,
    loudnessGainMb: 300,
    activePreset: true,
    createdAt: now,
    updatedAt: now,
  };
}

function validSettingsPayload(uid, now) {
  return {
    userId: uid,
    qualityProfile: "AUTO_ADAPTIVE",
    autoResume: true,
    offlineCacheLimitMb: 2048,
    preferredTransport: "AUTO",
    parkedOnlyEnforced: true,
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated users are denied all reads and writes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("videos").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_1").set(validVideoPayload(ALICE_UID, now))
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("eq_presets").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("settings").doc("preferences").get());
});

test("Owner can create, list, update, and delete synced videos with Kotlin-aligned payloads", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const vidRef = aliceDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_01");

  await assertSucceeds(vidRef.set(validVideoPayload(ALICE_UID, created)));

  // List matches repository query: collection("users").document(uid).collection("videos")
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("videos").get());

  // Partial progress update
  await assertSucceeds(
    vidRef.update({
      lastPositionMs: 360000,
      audioTrackIndex: 1,
      subtitleTrackIndex: 0,
      playbackSpeed: 1.25,
      aspectMode: "FILL",
      audioDelayMs: -40,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject @DocumentId "id" key injection on create
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_02").set({
      ...validVideoPayload(ALICE_UID, created),
      id: "vid_02",
    })
  );

  // Reject immutable createdAt modification on update
  await assertFails(
    vidRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future server timestamp tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("videos").doc("vid_03").set({
      ...validVideoPayload(ALICE_UID, new Date(Date.now() + 60000)),
    })
  );

  // Reject ghost field on update
  await assertFails(
    vidRef.update({
      lastPositionMs: 400000,
      ghostAdminFlag: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(vidRef.delete());
});

test("Cross-user access to videos, eq_presets, and settings is strictly denied", async () => {
  const now = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(BOB_UID).collection("videos").doc("bob_vid").set(validVideoPayload(BOB_UID, now));
    await db.collection("users").doc(BOB_UID).collection("eq_presets").doc("bob_eq").set(validPresetPayload(BOB_UID, now));
    await db.collection("users").doc(BOB_UID).collection("settings").doc("preferences").set(validSettingsPayload(BOB_UID, now));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("videos").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("videos").doc("bob_vid").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("videos").doc("bob_vid").update({
      lastPositionMs: 0,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("eq_presets").doc("bob_eq").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("settings").doc("preferences").get());
});

test("Owner can create and update EQ presets and settings while safety lock invariant holds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const eqRef = aliceDb.collection("users").doc(ALICE_UID).collection("eq_presets").doc("windsor_cabin");
  const settingsRef = aliceDb.collection("users").doc(ALICE_UID).collection("settings").doc("preferences");

  await assertSucceeds(eqRef.set(validPresetPayload(ALICE_UID, created)));
  await assertSucceeds(
    eqRef.update({
      bandLevelsMb: [300, 150, 0, 200, 350],
      bassBoostStrength: 600,
      loudnessGainMb: 400,
      activePreset: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject oversized EQ band array (> 10)
  await assertFails(
    eqRef.update({
      bandLevelsMb: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(settingsRef.set(validSettingsPayload(ALICE_UID, created)));
  await assertSucceeds(
    settingsRef.update({
      qualityProfile: "USB_C_1080P60",
      preferredTransport: "USB_C_DISPLAY",
      offlineCacheLimitMb: 4096,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject disabling parked-only safety lock
  await assertFails(
    settingsRef.update({
      parkedOnlyEnforced: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Owner can manage custom playlists while cross-user and invalid payloads are denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("charging_break");

  await assertSucceeds(
    plRef.set({
      userId: ALICE_UID,
      name: "Charging Break",
      videoIds: ["vid_01", "vid_02"],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("playlists").get());

  await assertSucceeds(
    plRef.update({
      name: "EV Fast Charge Mix",
      videoIds: ["vid_02"],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    plRef.update({
      name: "",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("playlists").get());
  await assertSucceeds(plRef.delete());
});
