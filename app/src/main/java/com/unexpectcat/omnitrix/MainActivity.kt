package com.unexpectcat.omnitrix

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {

    private lateinit var omnitrix: OmnitrixView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        omnitrix = OmnitrixView(this)
        setContentView(omnitrix)
        omnitrix.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        omnitrix.start()
    }

    override fun onPause() {
        omnitrix.stop()
        super.onPause()
    }
}
