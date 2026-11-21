"""Gera o vídeo de um devocional: voz, pad instrumental, fundo da Bíblia AGB e legenda.

O arquivo de entrada é o mesmo do publicador: título na primeira linha e o texto
nas seguintes. A voz vem do ElevenLabs quando a chave existe em
C:\\Winvox\\Treino\\chaves-play-biblia\\elevenlabs.txt (ou em ELEVENLABS_API_KEY);
sem chave, ou se a chamada falhar, usa a voz neural da Microsoft (edge-tts).
O vídeo sai em pé, 1080x1920, na pasta videos do projeto.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
BACKGROUND = ROOT / "android/app/src/main/res/drawable-nodpi/papel_de_parede.webp"
OUTPUT_DIR = ROOT / "videos"
KEY_FILE = Path(r"C:\Winvox\Treino\chaves-play-biblia\elevenlabs.txt")
ELEVEN_VOICES = {"adam": "pNInz6obpgDQGcFmaJgB"}
ELEVEN_MODEL = "eleven_multilingual_v2"
EDGE_VOICE = "pt-BR-FranciscaNeural"
VOICE_DELAY = 1.5
PAD_VOLUME = 0.26
SAMPLE_RATE = 44100


def eleven_key() -> str | None:
    """Lê a chave sem nunca imprimi-la."""
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key and KEY_FILE.is_file():
        key = KEY_FILE.read_text(encoding="utf-8").strip()
    return key or None


def srt_time(seconds: float) -> str:
    ms = int(round(seconds * 1000))
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def write_srt(cues: list[tuple[float, float, str]], path: Path) -> None:
    lines = []
    for index, (start, end, text) in enumerate(cues, 1):
        lines += [str(index), f"{srt_time(start)} --> {srt_time(end)}", text, ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def eleven_tts(text: str, voice: str, key: str, audio: Path, srt: Path) -> None:
    """Gera a fala e monta a legenda frase a frase com os tempos por caractere."""
    voice_id = ELEVEN_VOICES.get(voice.lower(), voice)
    body = json.dumps({
        "text": text,
        "model_id": ELEVEN_MODEL,
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
    }).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/with-timestamps?output_format=mp3_44100_128",
        data=body,
        headers={"xi-api-key": key, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        payload = json.loads(response.read().decode("utf-8"))
    audio.write_bytes(base64.b64decode(payload["audio_base64"]))
    align = payload["alignment"]
    chars, starts, ends = align["characters"], align["character_start_times_seconds"], align["character_end_times_seconds"]
    cues, buffer, cue_start = [], "", None
    for char, start, end in zip(chars, starts, ends):
        if cue_start is None and char.strip():
            cue_start = start
        buffer += char
        # Fecha a legenda no fim de frase ou de linha.
        if char in ".?!\n" and buffer.strip():
            cues.append((cue_start, end, buffer.strip()))
            buffer, cue_start = "", None
    if buffer.strip():
        cues.append((cue_start or 0.0, ends[-1], buffer.strip()))
    write_srt(cues, srt)


def edge_tts(text_file: Path, voice: str, audio: Path, srt: Path) -> None:
    subprocess.run(
        ["edge-tts", "--voice", voice, "--rate=-5%", "-f", str(text_file),
         "--write-media", str(audio), "--write-subtitles", str(srt)],
        check=True,
    )


def make_pad(seconds: float, path: Path) -> None:
    """Pad ambiente original (C - Am - F - G), lento e sem direitos de terceiros."""
    chords = [
        [65.41, 130.81, 196.00, 261.63, 329.63, 392.00],
        [55.00, 110.00, 164.81, 220.00, 261.63, 329.63],
        [43.65, 87.31, 174.61, 220.00, 261.63, 349.23],
        [49.00, 98.00, 146.83, 196.00, 246.94, 293.66],
    ]
    chord_len, fade_len = 8.0, 3.0
    total = int(SAMPLE_RATE * seconds)
    t = np.arange(total) / SAMPLE_RATE
    mix = np.zeros(total)
    start, index = 0.0, 0
    while start < seconds:
        a = int(max(0.0, start - fade_len) * SAMPLE_RATE)
        b = int(min(seconds, start + chord_len + fade_len) * SAMPLE_RATE)
        seg = t[a:b]
        tone = np.zeros(len(seg))
        for freq in chords[index % len(chords)]:
            for detune in (-0.25, 0.0, 0.25):
                f = freq * 2 ** (detune / 12)
                tone += np.sin(2 * np.pi * f * seg) + 0.25 * np.sin(4 * np.pi * f * seg)
        env = np.ones(len(seg))
        k = min(int(fade_len * SAMPLE_RATE), len(seg) // 2)
        ramp = np.sin(np.linspace(0, np.pi / 2, k)) ** 2
        env[:k] *= ramp
        env[-k:] *= ramp[::-1]
        mix[a:b] += tone * env
        start += chord_len
        index += 1
    mix *= 0.85 + 0.15 * np.sin(2 * np.pi * 0.07 * t)
    mix = np.convolve(mix, np.ones(40) / 40, mode="same")
    mix /= np.max(np.abs(mix)) + 1e-9
    edge = int(4 * SAMPLE_RATE)
    mix[:edge] *= np.linspace(0, 1, edge)
    mix[-edge:] *= np.linspace(1, 0, edge)
    data = (mix * 0.8 * 32767).astype(np.int16)
    stereo = np.column_stack([data, np.roll(data, int(0.012 * SAMPLE_RATE))]).ravel()
    with wave.open(str(path), "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(stereo.tobytes())


def duration(path: Path) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True,
    )
    return float(result.stdout.strip())


def shift_srt(source: Path, target: Path, offset: float) -> None:
    def move(match):
        h, m, s, ms = map(int, match.groups())
        return srt_time(h * 3600 + m * 60 + s + ms / 1000 + offset)

    text = source.read_text(encoding="utf-8")
    target.write_text(re.sub(r"(\d+):(\d+):(\d+),(\d+)", move, text), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("arquivo", type=Path, help="Texto do devocional: título na primeira linha.")
    parser.add_argument("--voz", default="adam", help="Voz do ElevenLabs (nome ou id). Padrão: adam.")
    parser.add_argument("--edge", action="store_true", help="Força a voz da Microsoft.")
    parser.add_argument("--voz-edge", default=EDGE_VOICE)
    args = parser.parse_args()

    title = args.arquivo.read_text(encoding="utf-8-sig").strip().splitlines()[0].strip()
    text = args.arquivo.read_text(encoding="utf-8-sig").strip()
    OUTPUT_DIR.mkdir(exist_ok=True)
    safe_title = re.sub(r'[<>:"/\\|?*]', "", title).strip() or "devocional"
    output = OUTPUT_DIR / f"{safe_title}.mp4"

    with tempfile.TemporaryDirectory() as folder:
        work = Path(folder)
        audio, srt = work / "voz.mp3", work / "voz.srt"
        key = None if args.edge else eleven_key()
        engine = "Microsoft"
        if key:
            try:
                eleven_tts(text, args.voz, key, audio, srt)
                engine = f"ElevenLabs ({args.voz})"
            except Exception as error:
                print(f"ElevenLabs falhou ({type(error).__name__}: {getattr(error, 'code', '')}). Usando a voz da Microsoft.")
        if engine == "Microsoft":
            edge_tts(args.arquivo, args.voz_edge, audio, srt)
        total = round(duration(audio) + VOICE_DELAY + 3.5)
        make_pad(total, work / "pad.wav")
        shift_srt(srt, work / "legenda.srt", VOICE_DELAY)
        shutil.copy(BACKGROUND, work / "fundo.webp")
        style = ("FontName=Arial,FontSize=13,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
                 "BorderStyle=1,Outline=2,Shadow=1,Alignment=10,MarginL=15,MarginR=15")
        delay = int(VOICE_DELAY * 1000)
        graph = (
            "[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,eq=brightness=-0.12,"
            f"subtitles=legenda.srt:force_style='{style}',format=yuv420p[v];"
            f"[2:a]aecho=0.8:0.7:120|240:0.35|0.25,volume={PAD_VOLUME}[pad];"
            f"[1:a]adelay={delay}|{delay},apad=pad_dur=3[voz];"
            "[voz][pad]amix=inputs=2:duration=longest:normalize=0[a]"
        )
        subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-loop", "1", "-i", "fundo.webp", "-i", "voz.mp3", "-i", "pad.wav",
             "-filter_complex", graph, "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "medium",
             "-tune", "stillimage", "-r", "25", "-c:a", "aac", "-b:a", "160k", "-t", str(total), "video.mp4"],
            cwd=work, check=True,
        )
        shutil.move(str(work / "video.mp4"), output)
    print(f"Voz: {engine}")
    print(f"Duração: {total // 60} min {total % 60} s")
    print(f"Vídeo salvo em: {output}")


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    main()
