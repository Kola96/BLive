package com.blive.tv.utils

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.blive.tv.R

object ToastHelper {
    fun showTextToast(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val view = LayoutInflater.from(context).inflate(R.layout.toast_text_only, null)
        val textView = view.findViewById<TextView>(R.id.toast_message)
        textView.text = message
        Toast(context.applicationContext).apply {
            this.duration = duration
            setView(view)
            show()
        }
    }
}
