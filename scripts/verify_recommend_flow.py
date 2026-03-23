#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


OE = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52,
]


def http_get(url: str, headers: dict) -> str:
    req = Request(url=url, method="GET", headers=headers)
    with urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def http_get_with_meta(url: str, headers: dict) -> tuple[str, dict]:
    req = Request(url=url, method="GET", headers=headers)
    with urlopen(req, timeout=20) as resp:
        text = resp.read().decode("utf-8", errors="ignore")
        return text, dict(resp.headers.items())


def merge_cookie(base_cookie: str, key: str, value: str) -> str:
    if not value:
        return base_cookie
    cookie_parts = [p.strip() for p in base_cookie.split(";") if p.strip()]
    cookie_map = {}
    for part in cookie_parts:
        if "=" in part:
            k, v = part.split("=", 1)
            cookie_map[k.strip()] = v.strip()
    cookie_map[key] = value
    return "; ".join([f"{k}={v}" for k, v in cookie_map.items()])


def extract_w_webid(raw_html: str) -> str:
    match = re.search(r'\{"access_id":"[^"]+"\}', raw_html)
    if not match:
        return ""
    return json.loads(match.group(0)).get("access_id", "")


def parse_wbi_keys(nav_text: str) -> tuple[str, str]:
    nav_json = json.loads(nav_text)
    data = nav_json.get("data", {})
    wbi = data.get("wbi_img", {})
    img_url = wbi.get("img_url", "")
    sub_url = wbi.get("sub_url", "")
    img_key = Path(img_url).stem
    sub_key = Path(sub_url).stem
    return img_key, sub_key


def get_mixin_key(img_key: str, sub_key: str) -> str:
    source = img_key + sub_key
    return "".join([source[i] for i in OE if i < len(source)])[:32]


def sign_wbi(params: dict[str, str], img_key: str, sub_key: str) -> tuple[str, str]:
    wts = str(int(time.time()))
    params_with_wts = dict(params)
    params_with_wts["wts"] = wts
    sorted_params = []
    for key in sorted(params_with_wts.keys()):
        value = params_with_wts[key]
        value = "".join([c for c in value if c not in "!'()*"])
        sorted_params.append((key, value))
    query_string = urlencode(sorted_params)
    w_rid = hashlib.md5((query_string + get_mixin_key(img_key, sub_key)).encode("utf-8")).hexdigest()
    return w_rid, wts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cookie", required=True)
    parser.add_argument("--page", default="1")
    parser.add_argument("--page-size", default="30")
    parser.add_argument("--auto-spi-cookies", action="store_true")
    args = parser.parse_args()

    ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
    cookie_header = args.cookie

    common_headers = {
        "User-Agent": ua,
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Cookie": cookie_header,
    }

    if args.auto_spi_cookies:
        print("步骤0：请求 spi 获取 buvid3/buvid4")
        spi_text = http_get("https://api.bilibili.com/x/frontend/finger/spi", {**common_headers, "Referer": "https://www.bilibili.com/"})
        spi_json = json.loads(spi_text)
        spi_data = spi_json.get("data", {})
        buvid3 = spi_data.get("b_3", "")
        buvid4 = spi_data.get("b_4", "")
        cookie_header = merge_cookie(cookie_header, "buvid3", buvid3)
        cookie_header = merge_cookie(cookie_header, "buvid4", buvid4)
        common_headers["Cookie"] = cookie_header
        print(f"spi结果：buvid3={'有' if bool(buvid3) else '无'} buvid4={'有' if bool(buvid4) else '无'}")

    print("步骤1：请求 /all 提取 w_webid")
    all_text = http_get("https://live.bilibili.com/all", {**common_headers, "Referer": "https://live.bilibili.com/"})
    w_webid = extract_w_webid(all_text)
    if not w_webid:
        print("失败：未提取到 w_webid")
        return 1
    print(f"成功：w_webid 长度={len(w_webid)}")

    print("步骤2：请求 nav 提取 imgKey/subKey")
    nav_text = http_get("https://api.bilibili.com/x/web-interface/nav", {**common_headers, "Referer": "https://www.bilibili.com/"})
    img_key, sub_key = parse_wbi_keys(nav_text)
    if not img_key or not sub_key:
        print("失败：未提取到 imgKey/subKey")
        return 1
    print(f"成功：imgKey={img_key} subKey={sub_key}")

    unsigned = {
        "page": args.page,
        "page_size": args.page_size,
        "platform": "web",
        "web_location": "444.253",
        "w_webid": w_webid,
    }
    w_rid, wts = sign_wbi(unsigned, img_key, sub_key)
    request_params = dict(unsigned)
    request_params["w_rid"] = w_rid
    request_params["wts"] = wts
    query = urlencode(request_params)

    print("步骤3：请求推荐接口")
    recommend_text, recommend_headers = http_get_with_meta(
        f"https://api.live.bilibili.com/xlive/web-interface/v1/second/getUserRecommend?{query}",
        {
            **common_headers,
            "Origin": "https://live.bilibili.com",
            "Referer": "https://live.bilibili.com/",
        },
    )
    recommend_json = json.loads(recommend_text)
    code = recommend_json.get("code")
    message = recommend_json.get("message", "")
    data = recommend_json.get("data", {})
    rooms = data.get("list", [])
    print(
        "返回："
        f"code={code} message={message} list_count={len(rooms)} "
        f"bili-status-code={recommend_headers.get('bili-status-code', '')} "
        f"x-bili-gaia-vvoucher={recommend_headers.get('x-bili-gaia-vvoucher', '')}"
    )
    if rooms:
        first = rooms[0]
        print(
            "首条："
            f"roomid={first.get('roomid')} "
            f"uname={first.get('uname')} "
            f"title={first.get('title', '')[:30]}"
        )
    return 0 if code == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
