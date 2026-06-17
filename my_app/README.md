# Scam Call Guard

Scam Call Guard is a Flutter-based mobile application designed to detect potential scam calls and suspicious audio sources in real time. The application focuses on Android and combines speech recognition, text analysis, AI voice detection, and risk scoring to provide live scam risk assessment.

## Main Features

- Capture audio from microphone or screen audio.
- Offline Speech-to-Text using Sherpa-ONNX Zipformer.
- Scam text detection using a TextCNN classifier.
- Voice detection using Conformer and AASIST models.
- Aggregated `Voice Detect`, `Text Detect`, and `Total Detect` scores.
- Real-time debugging information:
  - Transcript
  - Text classification label
  - Conformer score
  - AASIST score
- Foreground service support for continuous detection while the application runs in the background.

---

## System Architecture

```text
AudioRecord / Screen Audio
        |
        +--> audio denoise/gain --> STT Zipformer --> transcript
        |                                      |
        |                                      +--> TextCNN --> Text Detect
        |
        +--> raw audio --> voice gate --> rolling voice window
                                      |
                                      +--> Conformer
                                      +--> AASIST
                                      +--> Voice Detect

Voice Detect + Text Detect --> Total Detect --> UI / Overlay
```

---

## Required Models

All model files must be manually placed in:

```text
android/app/src/main/assets/models/
```

Current model structure:

```text
models/
  anti_ai_mobile.pt
  aasist_mobile.pt
  aasist_mobile.ptl
  textcnn_mobile.pt
  textcnn_vocab.json
  scam_fasttext_mobile.ftz
  former-30M-RNNT-6000h/
    encoder-epoch-20-avg-10.int8.onnx
    decoder-epoch-20-avg-10.int8.onnx
    joiner-epoch-20-avg-10.int8.onnx
    bpe.model
    config.json
```

---

## Speech Recognition Model

### former-30M-RNNT-6000h

Sherpa-ONNX Zipformer/RNNT speech recognition model.

Purpose:

- Real-time speech transcription
- Fully offline operation on Android devices

Kotlin engine:

```text
SherpaZipformerSpeechRecognizerEngine
```

---

## Text Scam Detection Model

### textcnn_mobile.pt + textcnn_vocab.json

TextCNN-based scam conversation classifier.

Features:

- Exported from the original PyTorch training checkpoint.
- Android-compatible TorchScript format.
- Uses a custom vocabulary stored in `textcnn_vocab.json`.
- Existing UI still displays the result as "fastText" for backward compatibility.

Kotlin engine:

```text
TextCnnTorchClassifier
```

Text score calculation:

```text
logits = [normalLogit, scamLogit]

Text Detect = softmax(logits)[scam]
```

---

## AI Voice Detection Model

### anti_ai_mobile.pt

Conformer-based AI voice detector.

Input:

```text
[1, 64, 400]
```

Training labels:

```text
AI     = 0
Human  = 1
```

Android score calculation:

```text
conformerScore = 1 - sigmoid(conformerLogit)
```

Kotlin engine:

```text
TorchConformerDetector
```

---

## Deepfake Voice Detection Model

### aasist_mobile.pt / aasist_mobile.ptl

AASIST-based spoofing and deepfake speech detector.

Input:

```text
[1, 64600]
```

Training labels:

```text
AI     = 0
Human  = 1
```

Android behavior:

- Attempts to load `aasist_mobile.pt` first.
- Falls back to `aasist_mobile.ptl` if necessary.

Kotlin engine:

```text
TorchAasistDetector
```

Current calibrated score:

```text
margin = aiLogit - humanLogit

aasistScore = sigmoid((margin + 0.85) / 0.85)
```

---

## Text Detection Pipeline

1. Audio passes through the speech gate.
2. Zipformer generates partial and final transcripts.
3. The transcript buffer stores up to 600 recent characters.
4. The text classifier processes:
   - Maximum 40 most recent tokens
   - Minimum 5 tokens
5. Text normalization follows the training pipeline:
   - Convert to lowercase
   - Remove URLs
   - Replace numbers with `NUM`
   - Remove special characters
   - Reduce repeated characters
   - Normalize whitespace
6. TextCNN produces a scam probability score.
7. The application maintains a rolling average of the last 5 classifications.
8. If no new transcript is received for more than 12 seconds, the text score becomes stale and no longer contributes to the total score.

---

## Voice Detection Pipeline

1. Incoming audio is split into two branches:
   - Enhanced audio for speech recognition.
   - Raw audio for voice analysis to preserve AI/deepfake artifacts.
2. Voice gate verifies speech presence.
3. A rolling window of 64,600 samples is collected.
4. Conformer and AASIST run every 2 seconds.
5. Window-level score fusion:

```text
weighted = conformerScore * 0.45 + aasistScore * 0.55

strongest = max(conformerScore, aasistScore)

voiceWindowScore = max(weighted, strongest * 0.80)
```

6. Voice Detect displays a rolling average of the last 5 inferences (approximately the most recent 10 seconds).

---

## Total Detect

When both voice and text scores are available:

```text
Total Detect = (Voice Detect + Text Detect) / 2
```

When only one score is active:

```text
Total Detect = active score
```

Risk levels:

```text
>= 0.70  High Risk
>= 0.50  Watch
<  0.50  Normal
```

---

## Audio Processing

### Microphone Path

- AudioRecord
- 16 kHz mono PCM16
- Android NoiseSuppressor (if supported)
- Android AutomaticGainControl (if supported)

Real-time preprocessing:

- High-pass filter (~90 Hz)
- Low-pass filter (~4.2 kHz)
- Adaptive noise floor estimation
- Soft noise gate
- Adaptive RMS target: 0.08

### Screen Audio Path

- AudioPlaybackCaptureConfiguration
- 16 kHz mono PCM16
- Lightweight preprocessing

### Note

The application currently does not perform neural speech separation or music removal. Loud background music, television audio, or non-speech sounds may affect detection accuracy.

---

## Required Android Permissions

The following permissions are required:

- RECORD_AUDIO
- READ_PHONE_STATE
- POST_NOTIFICATIONS
- SYSTEM_ALERT_WINDOW
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_MICROPHONE
- FOREGROUND_SERVICE_MEDIA_PROJECTION
- WAKE_LOCK

Overlay permission must be granted for real-time detection display.

---

## Build

From:

```text
my_app/android
```

Run:

```powershell
.\gradlew.bat assembleDebug
```

Generated APK:

```text
build/app/outputs/flutter-apk/app-debug.apk
```

Or from:

```text
my_app
```

Run:

```powershell
flutter build apk --debug
```

---

## Replacing Models

### TextCNN

When training a new TextCNN model:

1. Export the checkpoint as:

```text
textcnn_mobile.pt
```

2. Export the vocabulary as:

```text
textcnn_vocab.json
```

3. Copy both files into:

```text
android/app/src/main/assets/models/
```

### AASIST

Export as:

```text
aasist_mobile.pt
```

or

```text
aasist_mobile.ptl
```

Input shape should remain:

```text
[1, 64600]
```

### Conformer

Export as:

```text
anti_ai_mobile.pt
```

Input feature shape must remain:

```text
[1, 64, 400]
```

After replacing any model, rebuild the APK and reinstall the application to ensure updated assets are packaged correctly.

---

## Current Limitations

- Android public APIs do not guarantee access to both sides of every phone call.
- Screen audio capture works for media applications such as YouTube, but many calling applications block audio capture.
- Voice detection performance depends heavily on audio quality and training data domain.
- Text detection performance depends on transcription quality.
- Incorrect transcripts may lead to incorrect text classification.
- The current AASIST calibration requires additional validation on larger real-world test sets.

---
## Disclaimer

This project is intended for research and educational purposes only. Detection results are probabilistic predictions generated by machine learning models and should not be considered definitive evidence of fraudulent activity.
