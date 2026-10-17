
package com.kevin.mpcmidiemulator
import android.content.Context
import android.media.*
import android.media.midi.*
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.media.AudioManager
import android.bluetooth.BluetoothAdapter
data class PadSound(var uri: String? = null, var name: String = "", var soundId: Int = 0)
class MainActivity : AppCompatActivity() {
    private lateinit var midiManager: MidiManager
    private lateinit var soundPool: SoundPool
    private var currentBank = 0
    private val banks = Array(3) { Array(16) { PadSound() } }
    private val padButtons = mutableListOf<Button>()
    private var selectedPad = -1
    private val midiNotes = (36..51).toList()
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { loadSound(selectedPad, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- ULTRA LOW LATENCY ---
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Force low latency buffer
        val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "128"

        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build())
            .build()

        setupPads()
        setupMidi()
    }

    private fun setupPads(){
        val grid = findViewById<android.widget.GridLayout>(R.id.padGrid)
        val labels = listOf(
            "ATOUCH SW\nSWING 60%","ATOUCH MODE\nLATCH","DARK\nSYNC","ECO\nTAP TEMPO",
            "VELOCITY 1\nOFF","VELOCITY 2\n54%","VELOCITY 3\n56%","VELOCITY 4\n58%",
            "PRESET 5\n1/16","PRESET 6\n1/16T","PRESET 7\n1/32","PRESET 8\n1/32T",
            "PRESET 1\n1/4","PRESET 2\n1/4T","PRESET 3\n1/8","PRESET 4\n1/8T"
        )
        val colors = listOf(R.drawable.pad_green,R.drawable.pad_green,R.drawable.pad_green,R.drawable.pad_green,
                            R.drawable.pad_cyan,R.drawable.pad_cyan,R.drawable.pad_cyan,R.drawable.pad_cyan,
                            R.drawable.pad_cyan,R.drawable.pad_cyan,R.drawable.pad_cyan,R.drawable.pad_cyan,
                            R.drawable.pad_pink,R.drawable.pad_pink,R.drawable.pad_pink,R.drawable.pad_pink)
        for(i in 0 until 16){
            val btn = Button(this).apply {
                text = labels[i]
                textSize = 6.5f
                setBackgroundResource(colors[i])
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width=0; height=0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
                    rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
                    setMargins(6,6,6,6)
                }
            }
            btn.setOnClickListener { trigger(i) }
            btn.setOnLongClickListener { selectedPad=i; pickAudio.launch("audio/*"); true }
            padButtons.add(btn)
            grid.addView(btn)
        }
        findViewById<Button>(R.id.btnBT).setOnClickListener {
            Toast.makeText(this,"Buscando JAM JUM BT...",Toast.LENGTH_SHORT).show()
            // BLE MIDI lib auto-detects BT devices as MIDI devices
        }
    }

    private fun trigger(i:Int){
        selectedPad=i
        val s = banks[currentBank][i]
        if(s.soundId!=0) soundPool.play(s.soundId,1f,1f,1,0,1f)
        padButtons[i].animate().scaleX(0.9f).scaleY(0.9f).setDuration(40).withEndAction {
            padButtons[i].animate().scaleX(1f).scaleY(1f).setDuration(40).start()
        }.start()
    }

    private fun loadSound(idx:Int, uri:Uri){
        try{
            val dest = java.io.File(filesDir,"bank${currentBank}_pad${idx}_${System.currentTimeMillis()}.wav")
            contentResolver.openInputStream(uri)?.use{ it.copyTo(dest.outputStream()) }
            val sid=soundPool.load(dest.absolutePath,1)
            banks[currentBank][idx]=PadSound(dest.absolutePath,dest.nameWithoutExtension,sid)
            Toast.makeText(this,"Sonido en PAD ${idx+1}",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){ Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() }
    }

    private fun setupMidi(){
        midiManager=getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager.registerDeviceCallback(object:MidiManager.DeviceCallback(){
            override fun onDeviceAdded(info: MidiDeviceInfo?){ info?.let{ openUsb(it) } }
        },null)
        for(d in midiManager.devices) openUsb(d)
    }

    private fun openUsb(info:MidiDeviceInfo){
        midiManager.openDevice(info,{ dev->
            findViewById<TextView>(R.id.midiStatus).text = "USB:${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.take(8)} BT:ON"
            for(p in info.ports) if(p.type==MidiDeviceInfo.PortInfo.TYPE_OUTPUT){
                dev.openOutputPort(p.portNumber).connect(object:MidiReceiver(){
                    override fun onSend(msg:ByteArray,off:Int,count:Int,ts:Long){
                        if(count>=3){
                            val st=msg[off].toInt() and 0xFF
                            val note=msg[off+1].toInt()
                            val vel=msg[off+2].toInt()
                            if((st and 0xF0)==0x90 && vel>0){
                                val idx=midiNotes.indexOf(note)
                                if(idx>=0) runOnUiThread{ trigger(idx) }
                            }
                        }
                    }
                })
            }
        },null)
    }
}
