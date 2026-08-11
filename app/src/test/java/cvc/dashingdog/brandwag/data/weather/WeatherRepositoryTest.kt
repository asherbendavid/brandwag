package cvc.dashingdog.brandwag.data.weather

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * These exercise the real Retrofit/OkHttp stack against a local MockWebServer,
 * per the reliability guide's "test with garbage/timeout input, not just happy
 * path" step. Each test maps to one row of the Step 3 network-outcomes table.
 */
class WeatherRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: WeatherRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val contentType = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }

        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val api = retrofit.create(OpenMeteoApiService::class.java)
        repository = WeatherRepository(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `500 response maps to HttpError not silently swallowed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(result is FetchResult.Failure.HttpError)
        assertTrue((result as FetchResult.Failure.HttpError).code == 500)
    }

    @Test
    fun `400 response maps to HttpError with code preserved for logging`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(result is FetchResult.Failure.HttpError)
        assertTrue((result as FetchResult.Failure.HttpError).code == 400)
    }

    @Test
    fun `connection timeout maps to NetworkError not HttpError`() = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(
            "a timeout must be distinguishable from an HTTP error, not just 'failed'",
            result is FetchResult.Failure.NetworkError
        )
    }

    @Test
    fun `malformed JSON body maps to MalformedResponse`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{ this is not valid json ][")
        )

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(result is FetchResult.Failure.MalformedResponse)
    }

    @Test
    fun `valid JSON missing hourly block maps to UnexpectedSchema not a fake success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"latitude":-33.9,"longitude":18.4}""")
        )

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(
            "missing hourly must surface as an error, never as an empty-but-successful result",
            result is FetchResult.Failure.UnexpectedSchema
        )
    }

    @Test
    fun `empty body maps to a failure not a crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val result = repository.fetchAndEvaluate(-33.9, 18.4)

        assertTrue(result is FetchResult.Failure)
    }
}