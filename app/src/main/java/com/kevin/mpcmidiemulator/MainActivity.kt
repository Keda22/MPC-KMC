
package com.kevin.mpcmidiemulator
import android.content.Context
import android.media.midi.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.GridLayout
import org.json.JSONObject
import java.io.File
data class PadSound(var uri: String? = null, var name: String = "", var soundId: Int = 0)
class MainActivity : AppCompatActivity() {
    private lateinit var midiManager: MidiManager
    private lateinit var soundPool: SoundPool
    private var currentBank = 0
    private val banks = Array(3) { Array(16) { PadSound() } }
    private val padButtons = mutableListOf<Button>()
    private var selectedPadForLoad = -1
    private var halfLevel = false
    private val midiNoteMap = (36..51).toList()
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { loadSoundToPad(selectedPadForLoad, it) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        soundPool = SoundPool.Builder().setMaxStreams(16).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        setupPads(); setupFunctionButtons(); setupBanks(); loadBanksFromPrefs(); setupMidi()
    }
    private fun setupPads(){
        val grid = findViewById<GridLayout>(R.id.padGrid)
        val labels = listOf("A13","A14","A15","A16","A09","A10","A11","A12","A05","A06","A07","A08","A01","A02","A03","A04")
        val visualToLogical = listOf(12,13,14,15,8,9,10,11,4,5,6,7,0,1,2,3)
        for(i in 0 until 16){
            val btn = Button(this).apply {
                text = labels[i]; textSize = 11f
                background = getDrawable(R.drawable.pad_background)
                layoutParams = GridLayout.LayoutParams().apply {
                    width=0; height=0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(8,8,8,8)
                }
            }
            val logical = visualToLogical[i]
            btn.setOnClickListener { triggerPad(logical) }
            btn.setOnLongClickListener { selectedPadForLoad=logical; pickAudio.launch("audio/*"); true }
            btn.tag = logical
            grid.addView(btn)
        }
        val sorted = Array<Button>(16){ Button(this) }
        for(i in 0 until grid.childCount){ val b = grid.getChildAt(i) as Button; sorted[b.tag as Int]=b }
        padButtons.addAll(sorted)
    }
    private fun setupFunctionButtons(){
        findViewById<Button>(R.id.btnHalfLevel).setOnClickListener { halfLevel=!halfLevel; it.isSelected=halfLevel }
        findViewById<Button>(R.id.btn16Level).apply { isSelected=true }
        findViewById<Button>(R.id.btnErase).setOnClickListener {
            if(selectedPadForLoad>=0){ banks[currentBank][selectedPadForLoad]=PadSound(); padButtons[selectedPadForLoad].text="A%02d".format(selectedPadForLoad+1); saveBanksToPrefs() }
        }
    }
    private fun setupBanks(){
        fun upd(){ findViewById<Button>(R.id.bankA).isSelected=currentBank==0; findViewById<Button>(R.id.bankB).isSelected=currentBank==1; findViewById<Button>(R.id.bankC).isSelected=currentBank==2; refreshLabels() }
        findViewById<Button>(R.id.bankA).setOnClickListener { currentBank=0; upd() }
        findViewById<Button>(R.id.bankB).setOnClickListener { currentBank=1; upd() }
        findViewById<Button>(R.id.bankC).setOnClickListener { currentBank=2; upd() }
        upd()
    }
    private fun refreshLabels(){ for(i in 0..15){ val s=banks[currentBank][i]; padButtons[i].text=if(s.name.isNotEmpty()) s.name.take(8) else "A%02d".format(i+1) } }
    private fun triggerPad(i:Int){
        selectedPadForLoad=i
        val pad=banks[currentBank][i]
        if(pad.soundId!=0){ val v=if(halfLevel)0.5f else 1f; soundPool.play(pad.soundId,v,v,1,0,1f) }
        padButtons[i].animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).withEndAction{ padButtons[i].animate().scaleX(1f).scaleY(1f).setDuration(60).start() }.start()
    }
    private fun loadSoundToPad(index:Int, uri:Uri){
        try{
            val fn="bank${currentBank}_pad${index}_${System.currentTimeMillis()}.wav"
            val dest=File(filesDir,fn)
            contentResolver.openInputStream(uri)?.use{ it.copyTo(dest.outputStream()) }
            val sid=soundPool.load(dest.absolutePath,1)
            banks[currentBank][index]=PadSound(dest.absolutePath, dest.nameWithoutExtension, sid)
            refreshLabels(); saveBanksToPrefs()
            Toast.makeText(this,"Sonido en A${index+1}",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){ Toast.makeText(this,"Error ${e.message}",Toast.LENGTH_LONG).show() }
    }
    private fun setupMidi(){
        midiManager=getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager.registerDeviceCallback(object:MidiManager.DeviceCallback(){
            override fun onDeviceAdded(d: MidiDeviceInfo?){ d?.let{ openMidi(it) } }
        },null)
        for(d in midiManager.devices) openMidi(d)
    }
    private fun openMidi(info:MidiDeviceInfo){
        midiManager.openDevice(info,{ dev->
            runOnUiThread{ findViewById<TextView>(R.id.midiStatus).text="MIDI: ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}" }
            for(p in info.ports) if(p.type==MidiDeviceInfo.PortInfo.TYPE_OUTPUT){
                dev.openOutputPort(p.portNumber).connect(object:MidiReceiver(){
                    override fun onSend(msg:ByteArray,off:Int,count:Int,ts:Long){
                        if(count>=3){
                            val st=msg[off].toInt() and 0xFF
                            val note=msg[off+1].toInt() and 0xFF
                            val vel=msg[off+2].toInt() and 0xFF
                            if((st and 0xF0)==0x90 && vel>0){
                                val idx=midiNoteMap.indexOf(note)
                                if(idx>=0) runOnUiThread{ triggerPad(idx) }
                            }
                        }
                    }
                })
            }
        },null)
    }
    private fun saveBanksToPrefs(){
        val prefs=getSharedPreferences("mpc_banks",Context.MODE_PRIVATE)
        val json=JSONObject()
        for(b in 0..2){ val arr=org.json.JSONArray(); for(p in 0..15){ val o=JSONObject(); o.put("uri",banks[b][p].uri?:""); o.put("name",banks[b][p].name); arr.put(o) }; json.put("bank$b",arr) }
        prefs.edit().putString("banks",json.toString()).apply()
    }
    private fun loadBanksFromPrefs(){
        val str=getSharedPreferences("mpc_banks",Context.MODE_PRIVATE).getString("banks",null)?:return
        try{
            val json=JSONObject(str)
            for(b in 0..2){ if(!json.has("bank$b")) continue; val arr=json.getJSONArray("bank$b"); for(p in 0..15){ val o=arr.getJSONObject(p); val uri=o.getString("uri"); if(uri.isNotEmpty()){ val f=File(uri); if(f.exists()){ val sid=soundPool.load(uri,1); banks[b][p]=PadSound(uri,o.getString("name"),sid) } } } }
            refreshLabels()
        }catch(_:Exception){}
    }
    override fun onDestroy(){ super.onDestroy(); soundPool.release() }
}
