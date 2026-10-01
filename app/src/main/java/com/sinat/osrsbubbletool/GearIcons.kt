package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

// The item pictures "Import my gear" compares your equipment with. They come with the app
// (assets/dps/icons/, one per "icon" in dps/equipment.json), put there by tools/dps/fetch_pictures.py.
//
// Most are from the OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc), at the
// exact version the app's item list was made from (COMMIT), so they always match it. A few that the
// repository has out of date (marked "wiki" in the item list) are the OSRS Wiki's instead.
// They're Jagex's artwork, included under Jagex's Fan Content Policy (see the Legal screen).
class GearIcons(context: Context) {

    companion object {
        // The version of the DPS calculator's repository the item list and pictures come from
        // (tools/dps/build_equipment_json.py and fetch_pictures.py read it from here)
        const val COMMIT = "89c3e25b344aea90d0189746e4b5f73dde0f0383"
        private const val FOLDER = "dps/icons"
    }

    private val assets = context.applicationContext.assets

    // Every picture that came with the app, listed once
    private val names: Set<String> by lazy { assets.list(FOLDER)?.toHashSet() ?: emptySet() }

    private fun fileName(name: String) = name.replace('/', '_')

    fun has(name: String) = fileName(name) in names

    // The picture, or null if it's missing or can't be read
    fun decode(name: String): Bitmap? = try {
        assets.open("$FOLDER/${fileName(name)}").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
}
