import os, subprocess
T = "צבע אדום. ירי רקטות וטילים. שדרות, איבים, ניר עם. היכנסו למרחב המוגן."
os.makedirs("samples/more", exist_ok=True)
# Google Translate
try:
    from gtts import gTTS
    gTTS(T, lang="iw").save("samples/more/GoogleTranslate.mp3")
    print("gtts ok")
except Exception as e: print("::warning::gtts", e)
# Meta MMS
try:
    import torch, scipy.io.wavfile as wf
    from transformers import VitsModel, AutoTokenizer
    m = VitsModel.from_pretrained("facebook/mms-tts-heb"); tok = AutoTokenizer.from_pretrained("facebook/mms-tts-heb")
    text = T
    if getattr(tok, "is_uroman", False):
        import uroman as ur
        text = ur.Uroman().romanize_string(T)
        print("uroman:", text)
    with torch.no_grad():
        out = m(**tok(text, return_tensors="pt")).waveform[0].numpy()
    wf.write("/tmp/mms.wav", m.config.sampling_rate, out)
    subprocess.run(["ffmpeg","-loglevel","error","-y","-i","/tmp/mms.wav","-b:a","48k","samples/more/Meta.mp3"], check=True)
    print("mms ok")
except Exception as e: print("::warning::mms", type(e).__name__, e)
