package dev.koeck.voicegpt.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import dev.koeck.voicegpt.presentation.theme.VoiceGPTTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*

val mainHandler = Handler(Looper.getMainLooper())

suspend fun sendToApi(
    prompt: String,
    onResponseReceived: (String) -> Unit,
    onRateLimitExceeded: () -> Unit
): Unit {
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(),
            JSONObject().put("prompt", prompt).toString()
        )

        val request = Request.Builder()
            .url("https://watch-gpt-api.vercel.app/api/prompt")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                mainHandler.post { onRateLimitExceeded.invoke() }
                return@withContext
            }

            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            val source = response.body?.source() ?: return@withContext
            while (!source.exhausted()) {
                val buffer = Buffer()
                source.read(buffer, 8192) // read 8192 bytes at once
                val chunk = buffer.readUtf8()

                // Here, `chunk` contains a piece of the response body.
                // You can process it as needed.
                mainHandler.post { onResponseReceived.invoke(chunk) }
            }
        }
    }
}




private const val REQUEST_CODE_SPEECH_INPUT = 1
private const val REQUEST_PERMISSION_RECORD_AUDIO = 2

class MainActivity : ComponentActivity() {

    private lateinit var textToSpeech: TextToSpeech

    // Add these lines
    var isLoading by mutableStateOf(false)
    var apiResponse by mutableStateOf<String>("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.ERROR) {
                textToSpeech.language = Locale.UK
            }
        }

        setContent {
            WearApp({ startSpeechToText() }, isLoading, apiResponse)
        }
    }

    fun startSpeechToText() {
        // Check if the Record Audio permission has been granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION_RECORD_AUDIO)
        } else {
            // Permission has already been granted, start speech recognition
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak something...")
            }
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_RECORD_AUDIO) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted, yay! Do the speech to text task you need to do.
                startSpeechToText()
            } else {
                // permission denied! Disable the functionality that depends on this permission.
                Toast.makeText(this, "Permission to record audio denied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == Activity.RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = result?.get(0) ?: ""

            isLoading = true
            apiResponse = ""
            var currentSentence = ""

            lifecycleScope.launch {
                sendToApi(spokenText, { response ->
                    Log.d("WatchGPT", "Received message: $response")
                    isLoading = false

                    apiResponse += response
                    currentSentence += response

                    if (Regex("[.!?]").containsMatchIn(currentSentence)) {
                        val splitted = currentSentence.split(Regex("[.!?]"))
                        Log.d("WatchGPT", "splitted 0: " + splitted[0])
                        textToSpeech.speak(splitted[0], TextToSpeech.QUEUE_ADD, null, "")
                        currentSentence = if (splitted.size > 1) splitted[1] else ""
                    }
                }, {
                    isLoading = false
                    Toast.makeText(this@MainActivity, "Rate limit exceeded", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }


    override fun onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WearApp(onMicClick: () -> Unit, isLoading: Boolean, apiResponse: String) {




    VoiceGPTTheme {
        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            if(apiResponse.isNotEmpty()) {
                val focusRequester = FocusRequester()
                val listState = rememberScalingLazyListState()
                val coroutineScope = rememberCoroutineScope()

                Scaffold(
                    positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
                ) {

                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier
                            .onRotaryScrollEvent {
                                coroutineScope.launch {
                                    listState.scrollBy(it.verticalScrollPixels)
                                }
                                true
                            }
                            .focusRequester(focusRequester)
                            .focusable(),
                    ) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                apiResponse.let {
                                    Text(text = it, modifier = Modifier.padding(20.dp), style = TextStyle(fontSize = 12.sp))
                                }
                                Button(onClick = { onMicClick() }, modifier = Modifier.padding(
                                    PaddingValues(bottom = 20.dp)
                                )) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }
            }else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Start asking WearAI", modifier= Modifier.padding(PaddingValues(bottom = 10.dp)))
                    Button(onClick = { onMicClick() },) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }
    }
}
