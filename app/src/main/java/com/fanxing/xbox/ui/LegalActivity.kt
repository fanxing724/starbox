package com.fanxing.xbox.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fanxing.xbox.databinding.ActivityLegalBinding

class LegalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        val b = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(b.root)

        val kind = intent.getStringExtra("kind") ?: "agreement"
        b.toolbar.title = Legal.title(kind)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.content.text = Legal.body(kind)
    }
}
