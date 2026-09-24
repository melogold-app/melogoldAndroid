import yt_dlp
import json


def download(quickjs_bin: str, video_id: str) -> str:
    opts = {"format": "bestaudio", "js_runtimes": {"quickjs": {"path": quickjs_bin}}}

    return json.dumps(
        yt_dlp.YoutubeDL(opts).extract_info(video_id, download=False), indent=4
    )

