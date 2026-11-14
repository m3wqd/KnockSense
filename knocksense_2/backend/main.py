import re
from collections import defaultdict
from threading import Lock
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from detector import decode_wav, sound_features, detect_tonal_alarm
import numpy as np

app = FastAPI(title='KnockSense 2.0')
lock = Lock()
profiles = defaultdict(lambda: {'examples': [], 'threshold': None})


def check_device(device_id: str):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,50}', device_id):
        raise HTTPException(422, 'Invalid device ID')


async def read_audio(audio: UploadFile):
    b = await audio.read(100_001)
    if len(b) > 100_000:
        raise HTTPException(413, 'WAV too large')
    try:
        return decode_wav(b)
    except (ValueError, EOFError, OSError) as exc:
        raise HTTPException(400, str(exc))


@app.get('/api/health')
def health():
    return {'status': 'ok'}


@app.post('/api/calibrate')
async def calibrate(audio: UploadFile = File(...), device_id: str = Form(...)):
    check_device(device_id)
    data, rate = await read_audio(audio)
    feat = sound_features(data, rate, 0.8)
    if feat['knocks'] < 3:
        raise HTTPException(422, 'Knock 3 times clearly during the recording')
    # Illustrative calibration from observed loudness; adaptive background floor remains active.
    amplitude = float(np.median(sorted(feat['strengths'], reverse=True)[:3]))
    with lock:
        profile = profiles[device_id]
        if len(profile['examples']) >= 3:
            profile['examples'].clear()
        profile['examples'].append(amplitude)
        count = len(profile['examples'])
        if count == 3:
            profile['threshold'] = round(float(np.clip(np.median(profile['examples']) * .28, .012, .12)), 5)
        return {'accepted': True, 'examples': count, 'ready': count == 3,
                'threshold': profile['threshold']}


@app.post('/api/detect')
async def detect(audio: UploadFile = File(...), device_id: str = Form(...),
                 sensitivity: float = Form(0.6), fire_mode: bool = Form(False)):
    check_device(device_id)
    if not 0 <= sensitivity <= 1:
        raise HTTPException(422, 'Sensitivity must be 0..1')
    data, rate = await read_audio(audio)
    with lock:
        threshold = profiles[device_id]['threshold']
    feat = sound_features(data, rate, sensitivity, threshold)
    return {'knock': feat['detected'], 'knocks': feat['knocks'],
            'fire_tone': bool(fire_mode and detect_tonal_alarm(data, rate)),
            'noise': feat['noise'], 'calibrated': threshold is not None}


@app.post('/api/reset')
def reset(device_id: str = Form(...)):
    check_device(device_id)
    with lock:
        profiles.pop(device_id, None)
    return {'ok': True}
