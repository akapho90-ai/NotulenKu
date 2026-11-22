const { initializeApp } = require("firebase-admin/app");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const { defineSecret, defineString } = require("firebase-functions/params");

const { createSupabaseClient, ServiceError } = require("./lib/supabaseClient");
const meetingService = require("./lib/meetingService");

initializeApp();

setGlobalOptions({
  region: "asia-southeast2",
  maxInstances: 10,
});

const supabaseUrlParam = defineString("SUPABASE_URL", {
  default: "https://ojmdiiwowiqvcxgexxgr.supabase.co",
});
const supabaseServiceRoleKey = defineSecret("SUPABASE_SERVICE_ROLE_KEY");

function getSupabase() {
  return createSupabaseClient({
    url: process.env.SUPABASE_URL || supabaseUrlParam.value(),
    serviceRoleKey: process.env.SUPABASE_SERVICE_ROLE_KEY || supabaseServiceRoleKey.value(),
  });
}

function wrapCallable(handler) {
  return onCall({ secrets: [supabaseServiceRoleKey] }, async (request) => {
    try {
      const supabase = getSupabase();
      return await handler({
        auth: request.auth,
        data: request.data,
        supabase,
      });
    } catch (err) {
      if (err instanceof ServiceError) {
        throw new HttpsError(err.code, err.message, err.details);
      }
      console.error("Unexpected meeting backend error:", err);
      throw new HttpsError(
        "internal",
        "Terjadi kesalahan server saat mengakses data notulen."
      );
    }
  });
}

exports.savePersonalMeeting = wrapCallable(meetingService.savePersonalMeeting);
exports.listPersonalMeetings = wrapCallable(meetingService.listPersonalMeetings);
exports.getPersonalMeetingDetail = wrapCallable(meetingService.getPersonalMeetingDetail);
exports.updatePersonalMeeting = wrapCallable(meetingService.updatePersonalMeeting);
exports.deletePersonalMeeting = wrapCallable(meetingService.deletePersonalMeeting);
exports.linkSupabaseAccount = wrapCallable(meetingService.linkSupabaseAccount);
