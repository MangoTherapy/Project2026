package com.example.project2026

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.net.HttpURLConnection
import java.net.URL

import org.json.JSONObject

class MainActivity : AppCompatActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fun sendRequest(): String {
            val url = URL("https://wss.vefx.ru:5134/android")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            val json = """{"def":"infotrack","port":5432}"""
            connection.outputStream.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            return connection.inputStream.bufferedReader().use {
                it.readText()
            }
        }

        fun fJson1(ar1:JSONObject): MutableMap<String, Any> {
            val data55  = mutableMapOf<String, Any>()
            println(ar1)
            ar1.keys().forEach { key ->
                println(key)
                println(ar1[key])
                data55[key]=ar1[key]
                println(ar1[key] is JSONObject)
            }
            println(data55)
            return data55
        }

        fun fJson(ar1:Any): MutableMap<String, Any> {
            val data52 = mutableMapOf<String, Any>()
            //println(ar1)
            //println(ar1 is JSONObject)
            //println(ar1 is String)
            val re1 = JSONObject(ar1 as String)
            //println(re1)
            //var dfg:JSONObject
            re1.keys().forEach { key ->
                data52[key] = re1.get(key)
                //println(key)
                //println(data52[key])
                if (data52[key] is JSONObject){
                    //println(1111111)
                    //println(data52[key])
                    //var dfg=data52[key]
                    data52[key]=fJson1(data52[key] as JSONObject)
                }
            }
            ///println(data52)
            return data52
        }


        fun fS048() {
            val textResponse = findViewById<TextView>(R.id.textResponse)
            textResponse.text = "Ожидаем ответ"
            Thread {
                while (true) {
                    try {
                        val response = sendRequest()
                        val egg=fJson(response)
                        println(egg)
                        val ret = egg["Return"] as MutableMap<*, *>
                        val data = ret["Track"] as String

                        runOnUiThread {
                            //val SGV=response.decodeFromString<Response>(jsonString)
                            //println(SGV.Return)
                            textResponse.text = data
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            textResponse.text = "Ошибка: ${e.message}"
                        }
                    }
                    Thread.sleep(60_000)
                }
            }.start()
        }

        fS048()

        val textTimestamp = findViewById<TextView>(R.id.textTimestamp)

        val handler = Handler(Looper.getMainLooper())

        val updateTime = object : Runnable {
            override fun run() {
                val currentTime = SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())

                textTimestamp.text = currentTime

                handler.postDelayed(this, 1000)
            }
        }

        handler.post(updateTime)

        val editText = findViewById<EditText>(R.id.editText)

        val buttonOpenSecond =
            findViewById<Button>(R.id.buttonOpenSecond)

        buttonOpenSecond.setOnClickListener {

            val text = editText.text.toString()

            if (text.isBlank()) {
                editText.error = "Введите текст"
                return@setOnClickListener
            }

            val intent = Intent(this, SecondActivity::class.java)

            intent.putExtra("text", text)

            startActivity(intent)
        }

        val buttonCall =
            findViewById<Button>(R.id.buttonCall)

        buttonCall.setOnClickListener {

            val phone = editText.text.toString()

            if (phone.isBlank()) {
                editText.error = "Введите номер телефона"
                return@setOnClickListener
            }

            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:$phone")
            )

            startActivity(intent)
        }
        val buttonShare = findViewById<Button>(R.id.buttonShare)

        buttonShare.setOnClickListener {

            val text = editText.text.toString()

            if (text.isBlank()) {
                editText.error = "Введите текст"
                return@setOnClickListener
            }

            val intent = Intent(Intent.ACTION_SEND)

            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, text)

            startActivity(Intent.createChooser(intent, "Поделиться через"))
        }
    }
}