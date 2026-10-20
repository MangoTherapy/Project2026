
package com.example.project2026

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TRACK_URL = "https://wss.vefx.ru:5134/android"
private const val IMAGE_URL = "https://wss.vefx.ru:5132/S145P/image"

private const val TRACK_REQUEST = """{"def":"infotrack","port":5432}"""
private const val IMAGE_REQUEST = """{"port":5432}"""

private const val UPDATE_INTERVAL = 10_000L
private const val TIMEOUT = 10_000
private const val HISTORY_LIMIT = 10
private const val HISTORY_PREFS = "track_history"
private const val HISTORY_KEY = "tracks"

private val json = Json

private val ItemShape = RoundedCornerShape(6.dp)

// MARK: Models

@Serializable
data class ServerResponse(
    @SerialName("Return")
    val result: TrackData
)

@Serializable
data class TrackData(
    val def: String,
    val port: Int,
    val track: String = "",
    val background: List<Int> = emptyList(),
    @SerialName("Track") val trackUpper: String? = null,
    @SerialName("Background") val backgroundUpper: List<Int>? = null
)

data class TrackInfoState(
    val track: String = "...",
    val background: Color = Color.White,
    val image: ImageBitmap? = null,
    val error: String = ""
)

private class NetworkException(
    message: String
) : Exception(message)

// MARK: Network

private fun post(url: String, body: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/json; charset=UTF-8"
        )

        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode

        if (code !in 200..299) {
            throw NetworkException("Ошибка сервера: HTTP $code")
        }

        return connection.inputStream.use { input ->
            input.readBytes()
        }
    } catch (e: NetworkException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw NetworkException("Превышено время ожидания")
    } catch (e: UnknownHostException) {
        throw NetworkException("Сервер не найден")
    } catch (e: IOException) {
        throw NetworkException("Ошибка сетевого соединения")
    } finally {
        connection.disconnect()
    }
}

private fun getTrack(): TrackData {
    val response = post(TRACK_URL, TRACK_REQUEST)

    return try {
        json.decodeFromString<ServerResponse>(
            String(response, Charsets.UTF_8)
        ).result
    } catch (e: Exception) {
        throw NetworkException("Некорректный ответ сервера")
    }
}

private fun getImage(): ImageBitmap {
    val data = post(IMAGE_URL, IMAGE_REQUEST)

    return BitmapFactory.decodeByteArray(data, 0, data.size)
        ?.asImageBitmap()
        ?: throw NetworkException("Не удалось обработать обложку")
}

// MARK: Data conversion

private fun background(rgb: List<Int>): Color {
    if (rgb.size < 3) {
        throw NetworkException("Некорректный цвет фона")
    }

    return Color(
        red = rgb[0].coerceIn(0, 255) / 255f,
        green = rgb[1].coerceIn(0, 255) / 255f,
        blue = rgb[2].coerceIn(0, 255) / 255f
    )
}

private fun networkError(e: Exception): String {
    return when (e) {
        is NetworkException -> e.message ?: "Ошибка сети"
        is SocketTimeoutException -> "Сервер не отвечает"
        is UnknownHostException -> "Сервер не найден"
        is IOException -> "Ошибка сетевого соединения"
        else -> "Ошибка получения данных"
    }
}

// MARK: Time

@Composable
private fun CurrentTime(): String {
    val preview = LocalInspectionMode.current

    var time by remember {
        mutableStateOf(if (preview) "19:56:00" else "")
    }

    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect

        while (true) {
            time = SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            delay(1000)
        }
    }

    return time
}

// MARK: Track state

@Composable
private fun TrackInfo(): TrackInfoState {
    val preview = LocalInspectionMode.current

    var info by remember {
        mutableStateOf(TrackInfoState())
    }

    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect

        while (true) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val trackRequest = async { getTrack() }
                    val imageRequest = async {
                        try {
                            getImage()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }

                    trackRequest.await() to imageRequest.await()
                }

                val (track, image) = result

                info = TrackInfoState(
                    track = (track.trackUpper ?: track.track).removeSuffix(".mp3"),
                    background = background(track.backgroundUpper ?: track.background),
                    image = image,
                    error = ""
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info = info.copy(error = networkError(e))
            }

            delay(UPDATE_INTERVAL)
        }
    }

    return info
}

// MARK: Input

@Composable
private fun InputBlock(
    text: String,
    onChange: (String) -> Unit
) {
    BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White, ItemShape),
        decorationBox = { field ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                field()
            }
        }
    )
}

// MARK: History (последние 10 треков)

private fun loadHistory(context: Context): List<String> {
    val stored = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        .getString(HISTORY_KEY, "[]") ?: "[]"

    return try {
        val array = JSONArray(stored)
        (0 until minOf(array.length(), HISTORY_LIMIT)).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveTrack(
    context: Context,
    history: List<String>,
    track: String
): List<String> {
    val name = track.trim()
    if (name.isEmpty() || name == "..." || history.firstOrNull() == name) {
        return history
    }

    val updated = (listOf(name) + history).take(HISTORY_LIMIT)
    context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(HISTORY_KEY, JSONArray(updated).toString())
        .apply()

    return updated
}

@Composable
private fun TrackHistoryItem(track: String) {
    // Пример: «Giuseppe Ottaviani - Panama - original mix»
    val parts = track.split(" - ", limit = 2)
    val artist = if (parts.size == 2) parts[0] else "Неизвестный исполнитель"
    val title = if (parts.size == 2) parts[1] else track

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, ItemShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(Color(0xFFEAEAEA), ItemShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "♫", fontSize = 30.sp, color = Color.Gray)
        }

        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                fontSize = 13.sp,
                color = Color.DarkGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Музыка",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// MARK: Buttons

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(Color.White, ItemShape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun openSecondScreen(
    context: android.content.Context,
    text: String
) {
    if (text.isBlank()) return

    context.startActivity(
        Intent(context, SecondActivity::class.java)
            .putExtra("text", text)
    )
}

private fun dialNumber(
    context: android.content.Context,
    text: String
) {
    if (text.isBlank()) return

    context.startActivity(
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$text"))
    )
}

private fun shareText(
    context: android.content.Context,
    text: String
) {
    if (text.isBlank()) return

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    context.startActivity(
        Intent.createChooser(intent, "Поделиться через")
    )
}

// MARK: Main screen

@Composable
fun MainScreen() {
    var input by remember { mutableStateOf("") }

    val time = CurrentTime()
    val info = TrackInfo()
    val context = LocalContext.current
    val preview = LocalInspectionMode.current

    var history by remember(context, preview) {
        mutableStateOf(if (preview) emptyList() else loadHistory(context))
    }

    // Записываем новый трек только при смене его названия.
    LaunchedEffect(info.track, preview) {
        if (!preview) {
            history = saveTrack(context, history, info.track)
        }
    }

    // Один LazyColumn прокручивает весь экран, включая историю и кнопки.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(info.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InputBlock(
                text = input,
                onChange = { input = it }
            )
        }

        item{
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, ItemShape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info.track,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = time,
                    fontSize = 14.sp
                )
            }

        }


        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(304.dp),
                contentAlignment = Alignment.Center
            ) {
                info.image?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = "Обложка текущего трека",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(280.dp)
                            .clip(ItemShape)
                    )
                }
            }
        }

        item {
            Text(
                text = "История треков в эфире",
                fontSize = 18.sp,
                color = Color.Black
            )
        }

        if (history.isEmpty()) {
            item {
                Text(
                    text = "История появится после получения первого трека",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }
        } else {
            items(history) { track ->
                TrackHistoryItem(track)
            }
        }

        item {
            Column {
                if (info.error.isNotEmpty()) {
                    Text(
                        text = info.error,
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton(
                        text = "Экран",
                        onClick = { openSecondScreen(context, input) },
                        modifier = Modifier.weight(1f)
                    )

                    ActionButton(
                        text = "Позвонить",
                        onClick = { dialNumber(context, input) },
                        modifier = Modifier.weight(1f)
                    )

                    ActionButton(
                        text = "Поделиться",
                        onClick = { shareText(context, input) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen()
}

// MARK: Activity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MainScreen()
        }
    }
}
