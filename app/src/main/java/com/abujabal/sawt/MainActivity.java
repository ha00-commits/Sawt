package com.abujabal.sawt;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
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

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private MediaRecorder recorder;
    private MediaPlayer player;
    private File currentFile;
    private boolean recording = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startTime = 0;

    private TextView tvTimer, tvFile;
    private SeekBar seek, sbVolume, sbEcho, sbPitch;
    private Button btnRecord, btnStop, btnPlay, btnBack, btnForward, btnShare, btnLive;

    private LoudnessEnhancer boost;
    private EnvironmentalReverb reverb;

    private boolean live = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private volatile float liveGain = 1.0f;
    private volatile float liveEcho = 0.0f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvTimer = findViewById(R.id.tvTimer);
        tvFile = findViewById(R.id.tvFile);
        seek = findViewById(R.id.seek);
        sbVolume = findViewById(R.id.sbVolume);
        sbEcho = findViewById(R.id.sbEcho);
        sbPitch = findViewById(R.id.sbPitch);

        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        btnPlay = findViewById(R.id.btnPlay);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnShare = findViewById(R.id.btnShare);
        btnLive = findViewById(R.id.btnLive);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> { stopRecording(); stopLive(); });
        btnPlay.setOnClickListener(v -> togglePlay());
        btnBack.setOnClickListener(v -> seekBy(-10000));
        btnForward.setOnClickListener(v -> seekBy(10000));
        btnShare.setOnClickListener(v -> shareFile());
        btnLive.setOnClickListener(v -> toggleLive());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (player != null && u) player.seekTo(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                liveGain = 0.2f + (p / 100f) * 2.0f;
                applyVolume(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        sbEcho.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                liveEcho = p / 100f;
                applyEcho(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        sbPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { applyPitch(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void startRecording() {
        if (recording || live) return;
        try {
            File dir = new File(getCacheDir(), "rec");
            if (!dir.exists()) dir.mkdirs();
            currentFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            startTime = System.currentTimeMillis();
            tvFile.setText(currentFile.getName());
            tick();
            Toast.makeText(this, "بدأ التسجيل", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التسجيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void tick() {
        if (!recording) return;
        long d = System.currentTimeMillis() - startTime;
        int s = (int)(d / 1000);
        tvTimer.setText(String.format("%02d:%02d", s / 60, s % 60));
        handler.postDelayed(this::tick, 500);
    }

    private void stopRecording() {
        if (!recording) return;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        recording = false;
        Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show();
    }

    private void togglePlay() {
        if (live) { Toast.makeText(this, "أوقف الوضع المباشر أولاً", Toast.LENGTH_SHORT).show(); return; }
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "سجّل أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player != null && player.isPlaying()) {
            player.pause();
            btnPlay.setText("▶");
            return;
        }
        if (player == null) {
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                player.setDataSource(currentFile.getAbsolutePath());
                player.prepare();
                seek.setMax(player.getDuration());
                player.setOnCompletionListener(mp -> btnPlay.setText("▶"));
                applyVolume(sbVolume.getProgress());
                applyEcho(sbEcho.getProgress());
                applyPitch(sbPitch.getProgress());
                updateSeek();
            } catch (Exception e) {
                Toast.makeText(this, "فشل التشغيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        player.start();
        btnPlay.setText("⏸");
    }

    private void updateSeek() {
        if (player != null && player.isPlaying()) {
            seek.setProgress(player.getCurrentPosition());
        }
        handler.postDelayed(this::updateSeek, 300);
    }

    private void seekBy(int ms) {
        if (player == null) return;
        int pos = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(pos);
    }

    private void applyVolume(int percent) {
        if (player == null) return;
        try {
            if (boost == null) boost = new LoudnessEnhancer(player.getAudioSessionId());
            int gain = (int)((percent - 50) * 20);
            boost.setTargetGain(Math.max(0, gain));
            boost.setEnabled(percent > 50);
        } catch (Exception ignored) {}
    }

    private void applyEcho(int percent) {
        if (player == null) return;
        try {
            if (reverb == null) reverb = new EnvironmentalReverb(0, player.getAudioSessionId());
            if (percent <= 0) { reverb.setEnabled(false); return; }
            reverb.setDecayTime(percent * 40);
            reverb.setReverbLevel((short)(percent * 60 - 3000));
            reverb.setDensity((short)1000);
            reverb.setDiffusion((short)1000);
            reverb.setEnabled(true);
        } catch (Exception ignored) {}
    }

    private void applyPitch(int percent) {
        if (player == null) return;
        try {
            float pitch = 0.5f + (percent / 100f);
            PlaybackParams pp = player.getPlaybackParams();
            pp.setPitch(pitch);
            pp.setSpeed(1.0f);
            player.setPlaybackParams(pp);
        } catch (Exception ignored) {}
    }

    private void shareFile() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد تسجيل", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", currentFile);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "مشاركة التسجيل"));
    }

    private void toggleLive() {
        if (live) { stopLive(); return; }
        if (recording) { Toast.makeText(this, "أوقف التسجيل أولاً", Toast.LENGTH_SHORT).show(); return; }

        int sampleRate = 44100;
        int bufSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufSize < 4096) bufSize = 4096;
        final int bufferSize = bufSize;

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                return;
            }

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioRecord.startRecording();
            audioTrack.play();
            live = true;
            btnLive.setText("⏹ إيقاف المباشر");

            final int delay = (int)(sampleRate * 0.25);
            final short[] echoBuf = new short[delay];
            final int[] echoIdx = {0};

            new Thread(() -> {
                short[] buf = new short[bufferSize / 2];
                while (live) {
                    int n = audioRecord.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    for (int i = 0; i < n; i++) {
                        float s = buf[i] * liveGain;
                        float e = echoBuf[echoIdx[0]] * liveEcho;
                        float mix = s + e;
                        if (mix > 32767) mix = 32767;
                        if (mix < -32768) mix = -32768;
                        short out = (short) mix;
                        echoBuf[echoIdx[0]] = out;
                        echoIdx[0] = (echoIdx[0] + 1) % delay;
                        buf[i] = out;
                    }
                    audioTrack.write(buf, 0, n);
                }
            }).start();

            Toast.makeText(this, "الوضع المباشر شغال — البس سماعة", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            live = false;
            Toast.makeText(this, "فشل المباشر: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopLive() {
        if (!live) return;
        live = false;
        btnLive.setText("🎙️ وضع مباشر (البس سماعة)");
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); } } catch (Exception ignored) {}
        audioRecord = null;
        audioTrack = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLive();
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        try { if (player != null) player.release(); } catch (Exception ignored) {}
    }
}
