package com.abujabal.sawt;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final int SR = 44100;
    private AudioRecord audioRecord;
    private boolean recording = false;
    private File currentFile;
    private Thread recThread;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startTime = 0;
    private volatile int micGainPercent = 50;

    private TextView tvTimer, tvFile;
    private SeekBar seek, sbBoost, sbEcho, sbPitch, sbMic, sbNoise;
    private Button btnRecord, btnStop, btnPlay, btnShare, btnExport;
    private Button btnChild, btnMan, btnRobot;

    private AudioTrack audioTrack;
    private Thread playThread;
    private volatile boolean playing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvTimer = findViewById(R.id.tvTimer);
        tvFile = findViewById(R.id.tvFile);
        seek = findViewById(R.id.seek);
        sbBoost = findViewById(R.id.sbBoost);
        sbEcho = findViewById(R.id.sbEcho);
        sbPitch = findViewById(R.id.sbPitch);
        sbMic = findViewById(R.id.sbMic);
        sbNoise = findViewById(R.id.sbNoise);

        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        btnPlay = findViewById(R.id.btnPlay);
        btnShare = findViewById(R.id.btnShare);
        btnExport = findViewById(R.id.btnExport);
        btnChild = findViewById(R.id.btnChild);
        btnMan = findViewById(R.id.btnMan);
        btnRobot = findViewById(R.id.btnRobot);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        sbMic.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { micGainPercent = p; }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnShare.setOnClickListener(v -> shareFile());
        btnExport.setOnClickListener(v -> exportProcessed());
        btnChild.setOnClickListener(v -> { sbPitch.setProgress(80); sbEcho.setProgress(30); });
        btnMan.setOnClickListener(v -> { sbPitch.setProgress(25); sbEcho.setProgress(10); });
        btnRobot.setOnClickListener(v -> { sbPitch.setProgress(50); sbEcho.setProgress(70); sbNoise.setProgress(20); });
    }

    private void startRecording() {
        if (recording) return;
        try {
            File dir = new File(getCacheDir(), "rec");
            if (!dir.exists()) dir.mkdirs();
            currentFile = new File(dir, "rec_" + System.currentTimeMillis() + ".wav");

            int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 4096);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            audioRecord.startRecording();
            recording = true;
            startTime = System.currentTimeMillis();
            tvFile.setText(currentFile.getName());
            tick();

            recThread = new Thread(() -> {
                byte[] buf = new byte[bufSize];
                try {
                    RandomAccessFile raf = new RandomAccessFile(currentFile, "rw");
                    raf.write(new byte[44]);
                    while (recording) {
                        int n = audioRecord.read(buf, 0, buf.length);
                        if (n > 0) raf.write(buf, 0, n);
                    }
                    long dataLen = raf.length() - 44;
                    raf.seek(0);
                    raf.write(wavHeader(dataLen));
                    raf.close();
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "خطأ تسجيل: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            recThread.start();
            Toast.makeText(this, "بدأ التسجيل", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التسجيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] wavHeader(long dataLen) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            o.write("RIFF".getBytes());
            o.write(le32(36 + dataLen));
            o.write("WAVE".getBytes());
            o.write("fmt ".getBytes());
            o.write(le32(16));
            o.write(le16(1));
            o.write(le16(1));
            o.write(le32(SR));
            o.write(le32(SR * 2));
            o.write(le16(2));
            o.write(le16(16));
            o.write("data".getBytes());
            o.write(le32(dataLen));
        } catch (Exception ignored) {}
        return o.toByteArray();
    }

    private byte[] le32(long v) {
        return new byte[]{(byte)(v & 0xff),(byte)((v>>8)&0xff),(byte)((v>>16)&0xff),(byte)((v>>24)&0xff)};
    }
    private byte[] le16(int v) {
        return new byte[]{(byte)(v & 0xff),(byte)((v>>8)&0xff)};
    }

    private void tick() {
        if (!recording) return;
        long d = System.currentTimeMillis() - startTime;
        int s = (int)(d/1000);
        tvTimer.setText(String.format("%02d:%02d", s/60, s%60));
        handler.postDelayed(this::tick, 500);
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
        audioRecord = null;
        Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show();
    }

    private void togglePlay() {
        if (playing) { stopPlay(); return; }
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "سجّل أولاً", Toast.LENGTH_SHORT).show(); return;
        }
        try {
            byte[] pcm = readPcm(currentFile);
            final float gain = 0.2f + (sbBoost.getProgress()/100f)*2.0f;
            final float echo = sbEcho.getProgress()/100f;
            final float pitch = 0.5f + (sbPitch.getProgress()/100f);
            final float noise = sbNoise.getProgress()/100f;
            final short[] data = process(pcm, gain, echo, pitch, noise, false);
            playing = true;
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.length*2)
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            audioTrack.play();
            playThread = new Thread(() -> {
                int off = 0;
                while (playing && off < data.length) {
                    int chunk = Math.min(2048, data.length - off);
                    audioTrack.write(data, off, chunk);
                    off += chunk;
                }
                playing = false;
            });
            playThread.start();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التشغيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlay() {
        playing = false;
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); } } catch (Exception ignored) {}
        audioTrack = null;
    }

    private byte[] readPcm(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] all = new byte[(int) f.length()];
        int r = in.read(all); in.close();
        byte[] pcm = new byte[Math.max(0, r - 44)];
        System.arraycopy(all, 44, pcm, 0, pcm.length);
        return pcm;
    }

    private short[] process(byte[] pcm, float gain, float echo, float pitch, float noise, boolean export) {
        int len = pcm.length / 2;
        short[] in = new short[len];
        for (int i = 0; i < len; i++) {
            in[i] = (short)((pcm[i*2] & 0xff) | (pcm[i*2+1] << 8));
        }
        int outLen = (int)(len / pitch);
        short[] out = new short[outLen];
        int delay = (int)(SR * 0.25);
        short[] echoBuf = new short[delay];
        int echoIdx = 0;
        Random rnd = new Random();
        for (int i = 0; i < outLen; i++) {
            int srcIdx = (int)(i * pitch);
            if (srcIdx >= len) break;
            float s = in[srcIdx] * gain;
            float e = echoBuf[echoIdx] * echo;
            float mix = s + e;
            if (noise > 0) mix += (rnd.nextFloat() - 0.5f) * 3000 * noise;
            if (mix > 32767) mix = 32767;
            if (mix < -32768) mix = -32768;
            short v = (short) mix;
            echoBuf[echoIdx] = v;
            echoIdx = (echoIdx + 1) % delay;
            out[i] = v;
        }
        return out;
    }

    private void exportProcessed() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "سجّل أولاً", Toast.LENGTH_SHORT).show(); return;
        }
        try {
            byte[] pcm = readPcm(currentFile);
            float gain = 0.2f + (sbBoost.getProgress()/100f)*2.0f;
            float echo = sbEcho.getProgress()/100f;
            float pitch = 0.5f + (sbPitch.getProgress()/100f);
            float noise = sbNoise.getProgress()/100f;
            short[] data = process(pcm, gain, echo, pitch, noise, true);
            byte[] outPcm = new byte[data.length*2];
            for (int i = 0; i < data.length; i++) {
                outPcm[i*2] = (byte)(data[i] & 0xff);
                outPcm[i*2+1] = (byte)((data[i] >> 8) & 0xff);
            }
            File out = new File(currentFile.getParent(), "out_" + System.currentTimeMillis() + ".wav");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(wavHeader(outPcm.length));
            fos.write(outPcm);
            fos.close();
            Toast.makeText(this, "تم الحفظ ✅", Toast.LENGTH_SHORT).show();
            shareSpecific(out);
        } catch (Exception e) {
            Toast.makeText(this, "فشل التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد تسجيل", Toast.LENGTH_SHORT).show(); return;
        }
        shareSpecific(currentFile);
    }

    private void shareSpecific(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "مشاركة الصوت"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        stopPlay();
    }
    }
