package com.traderank.app

import android.app.Activity
import android.os.Handler
import android.view.View
import org.json.JSONObject

/** Unity LevelPlay ka interface. Asli code sirf "full" flavor mein hai, "lite" mein UnityProvider.get() = null. */
interface UnityAds {
    fun rewarded(activity: Activity, main: Handler, p: JSONObject, done: (String) -> Unit)
    fun interstitial(activity: Activity, p: JSONObject, done: (String) -> Unit)
    fun banner(activity: Activity, p: JSONObject): View?
}
