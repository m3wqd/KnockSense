"""Experimental knock and tonal-alarm heuristics, NOT a life-safety device."""
import io
import wave
import numpy as np


def decode_wav(data: bytes):
    with wave.open(io.BytesIO(data), 'rb') as w:
        rate, ch, width, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        if rate != 16000 or ch != 1 or width != 2 or n > 48000:
            raise ValueError('Expected mono PCM16 WAV, 16 kHz, up to 3 seconds')
        raw = w.readframes(n)
    samples = np.frombuffer(raw, dtype='<i2').astype(np.float32) / 32768.0
    if len(samples) < 8000:
        raise ValueError('Audio too short')
    return samples, rate


def sound_features(audio, rate, sensitivity=0.6, calibrated_threshold=None):
    frame_size = 160  # 10 ms
    count = len(audio) // frame_size
    frames = audio[:count * frame_size].reshape(count, frame_size)
    rms = np.sqrt(np.mean(frames * frames, axis=1))
    peak = np.max(np.abs(frames), axis=1)
    noise = max(0.002, float(np.percentile(rms, 25)))
    threshold = max(0.012, noise * (5 - 2.5 * sensitivity))
    if calibrated_threshold is not None:
        threshold = max(threshold, float(calibrated_threshold))
    mask = (rms > threshold) & (peak > max(.045, noise * 5)) & (peak / np.maximum(rms, .001) > 1.65)
    hit_times, hit_strengths = [], []
    for i in np.flatnonzero(mask):
        t = i * frame_size / rate
        if not hit_times or t - hit_times[-1] >= 0.17:
            hit_times.append(t)
            hit_strengths.append(float(rms[i]))
    best = 0
    for t in hit_times:
        best = max(best, sum(1 for v in hit_times if t <= v <= t + 1.5))
    return {'knocks': int(best), 'detected': best >= 3,
            'threshold': round(threshold, 5), 'noise': round(noise, 5),
            'strengths': hit_strengths}


def detect_tonal_alarm(audio, rate):
    """Experimental tone detector. It cannot reliably distinguish fire alarms from music."""
    window = 1600  # 100 ms
    count = len(audio) // window
    if count < 10:
        return False
    freqs = np.fft.rfftfreq(window, 1 / rate)
    band = (freqs >= 900) & (freqs <= 4000)
    good = []
    for i in range(count):
        frame = audio[i * window:(i + 1) * window]
        if float(np.sqrt(np.mean(frame * frame))) < 0.025:
            good.append(None)
            continue
        power = np.abs(np.fft.rfft(frame * np.hanning(window))) ** 2
        band_power = power[band]
        if band_power.size == 0 or float(band_power.sum()) < 1e-6:
            good.append(None)
            continue
        j = int(np.argmax(band_power))
        dominance = float(band_power[j] / max(1e-9, band_power.sum()))
        good.append(float(freqs[band][j]) if dominance > 0.22 else None)
    # At least 8 adjacent stable tonal windows (0.8 s)
    streak, center = 0, None
    for f in good:
        if f is not None and (center is None or abs(center - f) < 130):
            streak += 1
            center = f if center is None else .8 * center + .2 * f
        else:
            streak, center = (1, f) if f is not None else (0, None)
        if streak >= 8:
            return True
    return False
