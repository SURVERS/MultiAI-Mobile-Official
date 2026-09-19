package app.multiai.mobile.network;

import org.json.JSONException;
import org.json.JSONObject;

final class DeviceBanStatus {
    private DeviceBanStatus() {}

    static boolean parse(JSONObject response) throws JSONException {
        // Missing or malformed status is not an explicit server-side unban.
        if (!Boolean.TRUE.equals(response.opt("ok"))
                || !(response.opt("banned") instanceof Boolean)) {
            throw new JSONException("Invalid device status response");
        }
        return response.getBoolean("banned");
    }
}
