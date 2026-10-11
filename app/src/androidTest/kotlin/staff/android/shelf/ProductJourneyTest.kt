package staff.android.shelf
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class ProductJourneyTest {
 @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun completeCaseJourney(){try{
            waitTag("stream");tap("stream");waitTag("player-status")
            compose.waitUntil(30000){compose.onAllNodesWithText("Ready to play").fetchSemanticsNodes().isNotEmpty()||compose.onAllNodesWithText("Playback finished").fetchSemanticsNodes().isNotEmpty()}
            tap("stop");tap("download");waitTag("offline")
            val manifest=JSONObject(File(context().filesDir,"offline-film.json").readText()).getJSONObject("film")
            val film=File(context().filesDir,manifest.getString("sha256")+".mp4")
            assertEquals(manifest.getInt("length").toLong(),film.length())
            command("corrupt",JSONObject().put("enabled",true));tap("download");waitReady()
            compose.onNodeWithText("Download was not verified. Your previous saved film remains available.").assertExists()
            request("/__control","{\"unavailable\":true}");tap("offline");waitTag("player-status")
            compose.waitUntil(30000){compose.onAllNodesWithText("Ready to play").fetchSemanticsNodes().isNotEmpty()||compose.onAllNodesWithText("Playback finished").fetchSemanticsNodes().isNotEmpty()}
            tap("stop");tap("remove");waitReady();assertEquals(true,JSONObject(File(context().filesDir,"offline-film.json").readText()).isNull("film"))
 }finally{request("/__control","{}")}}
 private fun context()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun current()=request("/view").getJSONObject("data")
 private fun command(action:String,args:JSONObject)=request("/commands",JSONObject().put("id",UUID.randomUUID().toString()).put("action",action).put("args",args).toString())
 private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("refresh", "lab-tab")) node.performClick() else node.performScrollTo().performClick()
    }
 private fun waitTag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
 private fun waitReady(){compose.waitUntil(45000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
 private fun request(path:String,body:String?=null):JSONObject{
  val connection=URL("http://10.0.2.2:8917$path").openConnection() as HttpURLConnection
  connection.connectTimeout=10000;connection.readTimeout=10000
  if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
  return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
 }
}
