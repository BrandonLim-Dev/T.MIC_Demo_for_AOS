package com.example.tmic_demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.example.tmic_demo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    // < Global Variables >
    // View Binding
    private lateinit var binding : ActivityMainBinding
    // End - < Global Variables >

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // Spinner
        val data = listOf("- 선택하세요 - ", "UDP", "TCP/IP")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, data)
        binding.spinner.adapter = adapter
        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                when (p2)
                {
                    // 선택안함
                    0 -> {
                        Log.i("spinner", "선택안함")
                    }
                    // UDP
                    1 -> {
                        Log.i("spinner", "UDP")
                    }
                    // TCP/IP
                    2 -> {
                        Log.i("spinner", "TCP/IP")
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val imm: InputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        Log.i("INFO","onPause()")
    }

    override fun onResume() {
        super.onResume()
        Log.i("INFO","onResume()")
    }

    override fun onStop() {
        super.onStop()
        Log.i("INFO","onStop()")
    }

    override fun onStart() {
        super.onStart()
        Log.i("INFO","onStart()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("INFO","onDestroy()")
    }

    override fun onRestart() {
        super.onRestart()
        Log.i("INFO","onRestart()")
    }
}