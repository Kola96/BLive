import hashlib
import time
import urllib.parse
import requests
import re

def get_mixin_key(orig: str):
    mixinKeyEncTab = [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    ]
    return ''.join([orig[i] for i in mixinKeyEncTab])[:32]

def get_wbi_keys():
    res = requests.get('https://api.bilibili.com/x/web-interface/nav', headers={"User-Agent": "Mozilla/5.0"}).json()
    img_url = res['data']['wbi_img']['img_url']
    sub_url = res['data']['wbi_img']['sub_url']
    img_key = img_url.rsplit('/', 1)[1].split('.')[0]
    sub_key = sub_url.rsplit('/', 1)[1].split('.')[0]
    return img_key, sub_key

def main():
    img_key, sub_key = get_wbi_keys()
    mixin_key = get_mixin_key(img_key + sub_key)
    wts = int(time.time())
    
    res = requests.get('https://live.bilibili.com/all', headers={"User-Agent": "Mozilla/5.0"})
    html = res.text
    m = re.search(r'"access_id":"([a-zA-Z0-9]+)"', html)
    if not m:
        m = re.search(r'"w_webid":"([a-zA-Z0-9]+)"', html)
    w_webid = m.group(1) if m else "fake_webid"

    params = {
        "platform": "web",
        "parent_area_id": "0",
        "area_id": "0",
        "page": "1",
        "w_webid": w_webid,
        "wts": str(wts)
    }
    
    query = '&'.join([f'{k}={v}' for k, v in sorted(params.items())])
    w_rid = hashlib.md5((query + mixin_key).encode()).hexdigest()
    
    params['w_rid'] = w_rid
    url = 'https://api.live.bilibili.com/xlive/web-interface/v1/second/getList?' + urllib.parse.urlencode(params)
    print("URL:", url)
    
    resp = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}).json()
    print(resp.get('code'), resp.get('message'))
    if resp.get('data'):
        print("List length:", len(resp['data'].get('list', [])))

main()