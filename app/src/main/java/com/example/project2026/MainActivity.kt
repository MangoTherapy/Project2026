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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

// =====================================================================
// 1. НАСТРОЙКИ — основные значения можно менять здесь
// =====================================================================

// Адреса сервера и JSON-запросы
private const val TRACK_URL = "https://wss.vefx.ru:5134/android"
private const val IMAGE_URL = "https://wss.vefx.ru:5132/S145P/image"
private const val TRACK_REQUEST = """{"def":"infotrack","port":5432}"""
private const val IMAGE_REQUEST = """{"port":5432}"""

// Период обновления и тайм-аут соединения (миллисекунды)
private const val UPDATE_INTERVAL = 10_000L
private const val NETWORK_TIMEOUT = 10_000

// История: количество записей и место хранения
private const val HISTORY_LIMIT = 10
private const val HISTORY_PREFS = "track_history"
private const val HISTORY_KEY = "tracks"
private const val COVER_DIRECTORY = "track_covers"

// Оформление: размеры и отступы
private val CORNER_RADIUS = 6.dp
private val SCREEN_PADDING = 16.dp
private val SECTION_SPACING = 12.dp
private val HISTORY_SPACING = 6.dp       // Отступ МЕЖДУ треками
private val HISTORY_IMAGE_SIZE = 52.dp   // Размер обложки в истории
private val MAIN_IMAGE_SIZE = 280.dp     // Размер основной обложки
private val HISTORY_ITEM_PADDING = 8.dp // Внутренний отступ карточки

private val ItemShape = RoundedCornerShape(CORNER_RADIUS)
private val json = Json { ignoreUnknownKeys = true }

// =====================================================================
// 2. ДАННЫЕ — формат ответа сервера и состояние интерфейса
// =====================================================================

@Serializable
data class ServerResponse(
    @SerialName("Return") val result: TrackData
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

private data class RadioUpdate(
    val track: String,
    val background: Color,
    val coverBytes: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RadioUpdate

        if (track != other.track) return false
        if (background != other.background) return false
        if (!coverBytes.contentEquals(other.coverBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = track.hashCode()
        result = 31 * result + background.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

private data class TrackInfoState(
    val track: String = "...",
    val background: Color = Color.White,
    val image: ImageBitmap? = null,
    val imageHash: String = "",  // Отпечаток основной обложки в памяти
    val error: String = ""
)

private class NetworkException(message: String) : Exception(message)

// =====================================================================
// 3. СЕТЬ — получение названия, цвета и обложки с сервера
// =====================================================================

private fun post(url: String, body: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = NETWORK_TIMEOUT
        connection.readTimeout = NETWORK_TIMEOUT
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

        connection.outputStream.use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }

        if (connection.responseCode !in 200..299) {
            throw NetworkException("Ошибка сервера: HTTP ${connection.responseCode}")
        }

        return connection.inputStream.use { it.readBytes() }
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
    return try {
        val response = post(TRACK_URL, TRACK_REQUEST)
        json.decodeFromString<ServerResponse>(response.toString(Charsets.UTF_8)).result
    } catch (e: NetworkException) {
        throw e
    } catch (e: Exception) {
        throw NetworkException("Некорректный ответ сервера")
    }
}

// Получаем именно байты: так можно сравнить новую обложку с предыдущей.
private fun getCoverBytes(): ByteArray = post(IMAGE_URL, IMAGE_REQUEST)

// Вызывается в Dispatchers.IO, чтобы не блокировать интерфейс.
private suspend fun getRadioUpdate(): RadioUpdate = withContext(Dispatchers.IO) {
    val result = getTrack()
    val trackName = (result.trackUpper ?: result.track).trim().removeSuffix(".mp3")
    if (trackName.isBlank()) throw NetworkException("Название трека отсутствует")

    // Обложка может быть недоступна: название и фон при этом продолжают обновляться.
    val cover = try {
        getCoverBytes()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    RadioUpdate(
        track = trackName,
        background = toBackground(result.backgroundUpper ?: result.background),
        coverBytes = cover
    )
}

private fun toBackground(rgb: List<Int>): Color {
    if (rgb.size < 3) throw NetworkException("Некорректный цвет фона")
    return Color(
        red = rgb[0].coerceIn(0, 255) / 255f,
        green = rgb[1].coerceIn(0, 255) / 255f,
        blue = rgb[2].coerceIn(0, 255) / 255f
    )
}

private fun networkError(e: Exception): String = when (e) {
    is NetworkException -> e.message ?: "Ошибка сети"
    is IOException -> "Ошибка сетевого соединения"
    else -> "Ошибка получения данных"
}

// =====================================================================
// 4. ИСТОРИЯ — названия в SharedPreferences, обложки в файлах
// =====================================================================

// Старый формат истории (массив строк) оставлен без изменений.
// Благодаря этому названия уже накопленных треков не потеряются.
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

// Безопасное имя файла для обложки: зависит от ПОЛНОГО названия трека.
private fun coverFile(context: Context, track: String): File {
    val folder = File(context.filesDir, COVER_DIRECTORY)
    val nameHash = sha256(track.toByteArray(Charsets.UTF_8))
    return File(folder, "$nameHash.img")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

// Добавление только при смене названия. Старые записи старше лимита удаляются.
private suspend fun addToHistory(
    context: Context,
    history: List<String>,
    track: String
): List<String> = withContext(Dispatchers.IO) {
    if (track.isBlank() || history.firstOrNull() == track) return@withContext history

    val updated = (listOf(track) + history).take(HISTORY_LIMIT)
    context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit { putString(HISTORY_KEY, JSONArray(updated).toString()) }

    // Удаляем только обложки треков, которых БОЛЬШЕ НЕТ в списке.
    (history.toSet() - updated.toSet()).forEach { oldTrack ->
        coverFile(context, oldTrack).delete()
    }

    updated
}

// Сохраняем обложку только тогда, когда её содержимое изменилось.
// Возвращает true, если файл был создан или заменён.
private fun saveCover(context: Context, track: String, bytes: ByteArray): Boolean {
    val file = coverFile(context, track)
    if (file.isFile && file.readBytes().contentEquals(bytes)) return false

    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    return true
}

// Декодирование большой обложки — только когда сервер прислал новые байты.
private fun decodeCover(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

// Загружаем уменьшенное изображение для списка (меньше расход памяти).
private fun loadHistoryCover(context: Context, track: String): ImageBitmap? {
    val file = coverFile(context, track)
    if (!file.isFile) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)

    var sample = 1
    while (bounds.outWidth / sample > 180 && bounds.outHeight / sample > 180) {
        sample *= 2
    }

    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )?.asImageBitmap()
}

// =====================================================================
// 5. ВСПОМОГАТЕЛЬНЫЕ UI-БЛОКИ — время, поле ввода, текущий трек
// =====================================================================

@Composable
private fun CurrentTime(): String {
    val preview = LocalInspectionMode.current
    var time by remember { mutableStateOf(if (preview) "19:56:00" else "") }

    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect
        while (true) {
            time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1_000)
        }
    }
    return time
}

@Composable
private fun InputBlock(text: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = Color.Black, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().height(48.dp).background(Color.White, ItemShape),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { field() }
        }
    )
}

@Composable
private fun TrackBar(track: String, time: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White, ItemShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(text = time, fontSize = 14.sp)
    }
}

@Composable
private fun MainCover(image: ImageBitmap?) {
    Box(
        modifier = Modifier.fillMaxWidth().height(MAIN_IMAGE_SIZE + 24.dp),
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = "Обложка текущего трека",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(MAIN_IMAGE_SIZE).clip(ItemShape)
            )
        }
    }
}

// =====================================================================
// 6. ИСТОРИЯ В ИНТЕРФЕЙСЕ — компактная карточка трека
// =====================================================================

@Composable
private fun TrackHistoryItem(track: String, coverRevision: Int) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var cover by remember(track) { mutableStateOf<ImageBitmap?>(null) }

    // Читаем картинку с диска при появлении карточки или замене обложки.
    LaunchedEffect(track, coverRevision, preview) {
        if (preview) return@LaunchedEffect
        cover = try {
            withContext(Dispatchers.IO) { loadHistoryCover(context, track) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // Например: "Giuseppe Ottaviani - Panama - original mix"
    val parts = track.split(" - ", limit = 2)
    val artist = if (parts.size == 2) parts[0] else "Неизвестный исполнитель"
    val title = if (parts.size == 2) parts[1] else track

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White, ItemShape)
            .padding(HISTORY_ITEM_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(HISTORY_IMAGE_SIZE)
                .clip(ItemShape)
                .background(Color(0xFFEAEAEA)),
            contentAlignment = Alignment.Center
        ) {
            val savedCover = cover
            if (savedCover != null) {
                Image(
                    bitmap = savedCover,
                    contentDescription = "Обложка: $track",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("♫", fontSize = 26.sp, color = Color.Gray)
            }
        }

        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = title, fontSize = 14.sp, color = Color.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist, fontSize = 12.sp, color = Color.DarkGray,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(text = "Музыка", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

// =====================================================================
// 7. КНОПКИ — оставлены действия из твоего первоначального приложения
// =====================================================================

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(48.dp).background(Color.White, ItemShape).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = text, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

private fun openSecondScreen(context: Context, text: String) {
    if (text.isBlank()) return
    context.startActivity(Intent(context, SecondActivity::class.java).putExtra("text", text))
}

private fun dialNumber(context: Context, text: String) {
    if (text.isBlank()) return
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$text")))
}

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться через"))
}

@Composable
private fun BottomActions(input: String, error: String, context: Context) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (error.isNotBlank()) {
            Text(text = error, fontSize = 10.sp, color = Color.DarkGray)
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton("Экран", { openSecondScreen(context, input) }, Modifier.weight(1f))
            ActionButton("Позвонить", { dialNumber(context, input) }, Modifier.weight(1f))
            ActionButton("Поделиться", { shareText(context, input) }, Modifier.weight(1f))
        }
    }
}

// =====================================================================
// 8. ОСНОВНОЙ ЭКРАН — получение данных и сборка интерфейса
// =====================================================================

@Composable
fun MainScreen() {
    val context = LocalContext.current // Context Activity нужен для запуска кнопок
    val preview = LocalInspectionMode.current
    val time = CurrentTime()

    var input by remember { mutableStateOf("") }
    var info by remember { mutableStateOf(TrackInfoState()) }
    var history by remember(context, preview) {
        mutableStateOf(if (preview) emptyList() else loadHistory(context))
    }
    var coverRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect

        // Кешируем последнюю сохранённую пару, чтобы не читать файл каждые 10 сек.
        var savedTrack = ""
        var savedHash = ""

        while (true) {
            try {
                val update = getRadioUpdate()

                // Название и фон обновляются как прежде.
                info = info.copy(
                    track = update.track,
                    background = update.background,
                    error = ""
                )

                // В историю добавляем запись только при смене трека.
                history = addToHistory(context, history, update.track)

                update.coverBytes?.let { bytes ->
                    val newHash = withContext(Dispatchers.IO) { sha256(bytes) }

                    // Если сервер вернул ТУ ЖЕ обложку — оставляем ImageBitmap в памяти.
                    var validCover = newHash == info.imageHash
                    if (!validCover) {
                        val image = withContext(Dispatchers.IO) { decodeCover(bytes) }
                        if (image != null) {
                            info = info.copy(image = image, imageHash = newHash)
                            validCover = true
                        }
                    }

                    // Сохраняем только корректную картинку для данного трека.
                    // Даже при совпадении с основной обложкой отдельный файл
                    // истории может отсутствовать (например, у нового трека).
                    if (validCover && (savedTrack != update.track || savedHash != newHash)) {
                        try {
                            val changed = withContext(Dispatchers.IO) {
                                saveCover(context, update.track, bytes)
                            }
                            savedTrack = update.track
                            savedHash = newHash
                            if (changed) coverRevision++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: IOException) {
                            // Если файл не записался, повторим при следующем запросе.
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info = info.copy(error = networkError(e))
            }

            delay(UPDATE_INTERVAL)
        }
    }

    // Весь экран прокручивается; промежутки между карточками — 4dp.
    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(info.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(HISTORY_SPACING)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
                InputBlock(text = input, onChange = { input = it })
                TrackBar(track = info.track, time = time)
                MainCover(image = info.image)
                Text("История треков в эфире", fontSize = 18.sp, color = Color.Black)
            }
        }

        if (history.isEmpty()) {
            item {
                Text("История появится после первого трека", fontSize = 12.sp, color = Color.DarkGray)
            }
        } else {
            items(history) { track ->
                TrackHistoryItem(track = track, coverRevision = coverRevision)
            }
        }

        item { BottomActions(input = input, error = info.error, context = context) }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen()
}

// =====================================================================
// 9. ACTIVITY — запуск главного экрана
// =====================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }
}
