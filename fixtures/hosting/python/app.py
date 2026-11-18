from flask import Flask
import os
app=Flask(__name__)
@app.route('/')
def index(): return '<!doctype html><html lang="zh-CN"><head><title>Python 托管验收</title></head><body><h1>Python 网站运行成功</h1></body></html>'
app.run(host='0.0.0.0', port=int(os.environ.get('PORT','8000')))
